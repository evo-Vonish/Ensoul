package com.dwinovo.numen.entity;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.network.payload.NumenDeathPayload;
import com.dwinovo.numen.network.payload.NumenEventPayload;
import com.dwinovo.numen.network.payload.NumenRespawnPayload;
import com.dwinovo.numen.network.payload.CompanionListPayload;
import com.dwinovo.numen.platform.Services;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.vehicle.DismountHelper;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Coordinates companion lifecycle on top of {@link CompanionFactory} (body
 * spawn/despawn) and {@link CompanionRegistry} (the persistent index). The body
 * persists as a player {@code .dat}; the registry remembers it exists so it can
 * be recreated when its owner returns or a tool call arrives.
 */
@com.dwinovo.numen.api.Internal
public final class Companions {

    /** Ticks a dead companion stays down before respawning at its owner (~30 s). */
    private static final long RESPAWN_DELAY_TICKS = 30 * 20;

    // ---- 复活安全门 (刀④): don't respawn a companion straight into a meat-grinder around the owner ----
    /** Radius (blocks) the hostile density around the owner (and each candidate offset cell) is measured over. */
    private static final int RESPAWN_DANGER_RADIUS = 16;
    /** This many live hostiles within {@link #RESPAWN_DANGER_RADIUS} → the spot is "dangerous", trigger the gate. */
    private static final int RESPAWN_DANGER_THRESHOLD = 3;
    /** Look this far (blocks) around the owner for a hostile-sparse, better-lit safe cell to offset-respawn into. */
    private static final int RESPAWN_OFFSET_RADIUS = 24;
    /** When no safe cell exists, delay the respawn and re-check the owner's surroundings this often. */
    private static final long RESPAWN_RECHECK_TICKS = 100;

    /**
     * Per-companion "held" respawn deadline: a dead companion whose owner is surrounded and for whom no safe
     * offset cell was found waits until this game-time before the next re-check (so the density scan runs on the
     * {@link #RESPAWN_RECHECK_TICKS} cadence, not every tick). Cleared the moment it respawns. Server-thread only.
     */
    private static final Map<UUID, Long> respawnHoldUntil = new HashMap<>();

    private Companions() {}

    /**
     * Summon the owner's companion called {@code name}. IDEMPOTENT per (owner, name): if one already
     * exists it is reused — already live → returned as-is; dormant → brought back. Only a name with no
     * existing companion mints a fresh one. (The old "fresh random UUID every summon" minted same-name
     * duplicates that all respawned on login — that's the duplicate-companion bug.)
     */
    public static NumenPlayer summon(MinecraftServer server, UUID ownerUuid, String name,
                                      ServerLevel level, Vec3 pos) {
        UUID existing = findByOwnerName(server, ownerUuid, name);
        if (existing != null) {
            NumenPlayer body = respawn(server, existing);
            if (body != null) return body;
            CompanionRegistry.get(server).remove(existing);   // stale entry (no .dat) — replace it
        }
        UUID companionUuid = UUID.randomUUID();
        NumenPlayer body = CompanionFactory.spawn(server, companionUuid, name, ownerUuid, level, pos);
        CompanionRegistry.get(server).put(companionUuid,
                new CompanionRegistry.Entry(name, ownerUuid, level.dimension(), body.blockPosition()));
        return body;
    }

    /** Companion UUID of the owner's companion named {@code name}, or null if none. */
    private static UUID findByOwnerName(MinecraftServer server, UUID ownerUuid, String name) {
        for (Map.Entry<UUID, CompanionRegistry.Entry> e : CompanionRegistry.get(server).ownedBy(ownerUuid)) {
            if (e.getValue().name().equals(name)) return e.getKey();
        }
        return null;
    }

    /**
     * Bring a dormant companion back from its catalog entry + {@code .dat}
     * (position/inventory restored from disk). Returns the already-live body if
     * it is spawned, or {@code null} if it is unknown to the registry.
     */
    public static NumenPlayer respawn(MinecraftServer server, UUID companionUuid) {
        NumenPlayer live = NumenPlayer.findByUuid(server, companionUuid);
        if (live != null) return live;
        CompanionRegistry.Entry entry = CompanionRegistry.get(server).find(companionUuid);
        if (entry == null) return null;
        ServerLevel level = server.getLevel(entry.dimension());
        if (level == null) level = server.overworld();
        // pos=null: keep the position restored from the .dat.
        return CompanionFactory.spawn(server, companionUuid, entry.name(), entry.owner(), level, null);
    }

    /** When an owner logs in, bring back every companion of theirs. A companion that DIED while the owner
     *  was away (death state persisted in the registry — survives the logout) is respawned-at-owner now
     *  AND told why it died; a live one is just restored from its {@code .dat}. */
    public static void respawnAllOwnedBy(MinecraftServer server, UUID ownerUuid) {
        ServerPlayer owner = server.getPlayerList().getPlayer(ownerUuid);
        for (Map.Entry<UUID, CompanionRegistry.Entry> e : CompanionRegistry.get(server).ownedBy(ownerUuid)) {
            if (e.getValue().diedAt() > 0L) {
                if (owner != null) respawnDead(server, e.getKey(), e.getValue(), owner);
            } else {
                respawn(server, e.getKey());
            }
        }
    }

    /**
     * A companion just DIED (detected in {@link NumenPlayer#tick}). The death itself is left fully
     * vanilla — drops / a grave mod / keepInventory all run because it's a real ServerPlayer death.
     * We only: stop the brain (the owner's loop suspends on {@link NumenDeathPayload}, resolving the
     * in-flight tool call with the death cause), heal the body so its saved {@code .dat} is whole, and
     * queue a timed respawn at the owner. The corpse is removed AFTER this tick (a fake player isn't
     * auto-removed on death — it would sit at 0 HP forever waiting for a respawn packet that never comes).
     */
    public static void onDeath(NumenPlayer body) {
        MinecraftServer server = body.level().getServer();
        if (server == null) return;
        UUID uuid = body.getUUID();
        // Prefer the die()-tick snapshot: this poll runs a tick after the lethal hit, by which
        // point the fake player's combat tracker has degraded to the generic "X死了" (which a
        // pack producer's normalizer reduced to the useless cause "死"). The snapshot carries
        // the real per-source message ("X被僵尸杀死了"); when it equals the tracker's generic
        // message, either pick yields the same string, so the non-blank snapshot always wins.
        // Consumed (cleared) on read; tracker → "未知原因" fallbacks unchanged.
        String cause = body.consumeLastDeathMessage();
        if (cause == null || cause.isBlank()) {
            cause = body.getCombatTracker().getDeathMessage().getString();
        }
        if (cause == null || cause.isBlank()) cause = "未知原因";
        CompanionLifecycle.fireDeath(body);   // no result shipped — the death payload drives the client
        ServerPlayer owner = body.resolveOwnerPlayer();
        if (owner != null) {   // immediate, same-session (carries the respawn delay for the client countdown)
            Services.NETWORK.sendToPlayer(owner, new NumenDeathPayload(uuid, cause, RESPAWN_DELAY_TICKS * 50L));
        }
        // Persist the death (cause + game-time) in the world-saved registry so it survives a logout during
        // the respawn window — without this, a relog lost the pending state and the body silently respawned
        // "alive" with an empty inventory and no idea it had died.
        CompanionRegistry.get(server).markDead(uuid, cause, server.overworld().getGameTime());
        body.setHealth(body.getMaxHealth());             // saved .dat is a healthy body for the respawn
        server.execute(() -> CompanionFactory.despawn(server, body));   // remove the corpse safely after the tick
    }

    /**
     * Bring back any companion whose post-death timer has elapsed, AT ITS OWNER (covers dimension
     * follow too — it returns in whatever dimension the owner is now in). Owner offline → keep waiting
     * (their client-side brain can't run anyway); it respawns the moment they're back. Called each tick.
     */
    public static void tickRespawns(MinecraftServer server) {
        long now = server.overworld().getGameTime();
        for (Map.Entry<UUID, CompanionRegistry.Entry> e : CompanionRegistry.get(server).pendingDead()) {
            CompanionRegistry.Entry entry = e.getValue();
            if (now - entry.diedAt() < RESPAWN_DELAY_TICKS) continue;
            Long hold = respawnHoldUntil.get(e.getKey());
            if (hold != null && now < hold) continue;               // 复活安全门: owner still surrounded — re-check later
            ServerPlayer owner = server.getPlayerList().getPlayer(entry.owner());
            if (owner == null) continue;                            // owner offline — wait for login
            respawnDead(server, e.getKey(), entry, owner);
        }
    }

    /** Respawn a dead companion at its owner, clear the death state, and tell the brain it died + why
     *  (the cause rides the respawn payload, so it works even after a logout cleared the client's memory).
     *
     *  <p>复活安全门 (刀④): if the owner is standing in a hostile meat-grinder (≥{@link #RESPAWN_DANGER_THRESHOLD}
     *  hostiles within {@link #RESPAWN_DANGER_RADIUS}), the body is NOT dropped straight into it. Default policy:
     *  first try to OFFSET into a hostile-sparse, better-lit safe cell nearby ({@link #findSafeRespawnPos}); if
     *  none exists, DELAY the respawn and re-check on the {@link #RESPAWN_RECHECK_TICKS} cadence. Death drops are
     *  unaffected (they already landed where the companion died). */
    private static void respawnDead(MinecraftServer server, UUID uuid, CompanionRegistry.Entry entry,
                                    ServerPlayer owner) {
        ServerLevel level = (ServerLevel) owner.level();
        Vec3 pos = findSafeRespawnPos(level, owner);
        if (pos == null) {
            // No safe cell around a surrounded owner → delay and re-check later (owner has no live body to notify,
            // so this is logged; the client shows the companion still down until it can come back safely).
            respawnHoldUntil.put(uuid, level.getGameTime() + RESPAWN_RECHECK_TICKS);
            Constants.LOG.info("[numen-respawn] 复活暂缓:{} 的主人身边过于危险,{}t 后重试",
                    entry.name(), RESPAWN_RECHECK_TICKS);
            return;
        }
        respawnHoldUntil.remove(uuid);
        NumenPlayer body = CompanionFactory.spawn(server, uuid, entry.name(), entry.owner(), level, pos);
        body.setHealth(body.getMaxHealth());
        body.clearFire();
        CompanionRegistry.get(server).markAlive(uuid);
        syncRosterToOwner(server, owner);
        Services.NETWORK.sendToPlayer(owner, new NumenRespawnPayload(uuid, entry.deathCause()));
    }

    /**
     * The respawn position: beside the owner when it's safe enough, else a hostile-sparse + better-lit standable
     * cell within {@link #RESPAWN_OFFSET_RADIUS}, else {@code null} to signal "delay, nowhere safe yet".
     */
    private static Vec3 findSafeRespawnPos(ServerLevel level, ServerPlayer owner) {
        if (hostilesWithin(level, owner.position(), RESPAWN_DANGER_RADIUS) < RESPAWN_DANGER_THRESHOLD) {
            return owner.position();   // owner's surroundings are calm — respawn right beside them (unchanged)
        }
        // Surrounded: scan candidate columns outward for the safest reachable surface cell.
        BlockPos o = owner.blockPosition();
        Vec3 best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int radius = 8; radius <= RESPAWN_OFFSET_RADIUS; radius += 4) {
            for (int i = 0; i < 8; i++) {
                double ang = (2.0 * Math.PI * i) / 8.0;
                int x = o.getX() + (int) Math.round(Math.cos(ang) * radius);
                int z = o.getZ() + (int) Math.round(Math.sin(ang) * radius);
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                // Vanilla safe-placement (rejects lava/fire/cactus etc.); null → this column has no fit.
                Vec3 safe = DismountHelper.findSafeDismountLocation(owner.getType(), level, new BlockPos(x, y, z), true);
                if (safe == null) continue;
                int hostiles = hostilesWithin(level, safe, RESPAWN_DANGER_RADIUS);
                if (hostiles >= RESPAWN_DANGER_THRESHOLD) continue;   // not actually safer — skip
                int light = level.getMaxLocalRawBrightness(BlockPos.containing(safe));
                // Fewest nearby hostiles dominates, then brighter (fewer spawns), then closer to the owner.
                double score = -hostiles * 1000.0 + light * 10.0 + (RESPAWN_OFFSET_RADIUS - radius);
                if (score > bestScore) { bestScore = score; best = safe; }
            }
        }
        return best;   // null → nowhere sparse within range → caller delays
    }

    /** Count live hostile {@link Monster}s within {@code radius} blocks of {@code center}. */
    private static int hostilesWithin(ServerLevel level, Vec3 center, double radius) {
        AABB box = new AABB(center.x - radius, center.y - radius, center.z - radius,
                center.x + radius, center.y + radius, center.z + radius);
        int n = 0;
        for (Monster m : level.getEntitiesOfClass(Monster.class, box)) {
            if (m.isAlive()) n++;
        }
        return n;
    }

    /**
     * Push a snapshot of the owner's companions <em>currently live in the world</em>
     * (UUID + name) to their client, so the G panel reflects what actually exists
     * rather than a persisted list. The server is the only place that can answer
     * "which in-world players are NumenPlayers owned by you" — owner is a
     * server-side field — so it does the detection and ships the result. The
     * client treats each push as a complete replacement. Call after any change to
     * the live set (login-respawn, summon, despawn, death).
     */
    public static void syncRosterToOwner(MinecraftServer server, ServerPlayer owner) {
        List<CompanionListPayload.Entry> list = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p instanceof NumenPlayer a && a.isOwnedByPlayer(owner.getUUID())) {
                // Game mode from the LIVE body (authoritative); OP flag from the persisted registry.
                list.add(new CompanionListPayload.Entry(a.getUUID(), a.getName().getString(),
                        a.gameMode(), isOpEnabled(server, a.getUUID())));
            }
        }
        Services.NETWORK.sendToPlayer(owner, new CompanionListPayload(list));
    }

    // ---- per-companion capabilities (game mode + OP master switch) ----
    // Both are persisted in the CompanionRegistry entry (survive dormancy / logout) and exposed as static
    // flips so the G-panel UI (via a C→S payload) and commands can drive them. The OP flag is consumed by
    // the tool pack's run_command permission gate; the engine only stores it (the tier dial stays in the pack).

    /**
     * Set a companion's game mode (survival ⇄ creative), persisting it in the registry AND applying it to the
     * live body if it is currently spawned. Dormant → registry only; it takes effect on the next spawn (see
     * {@link CompanionFactory#spawn}). CREATIVE on the live body grants real invulnerability + flight +
     * no-hunger via vanilla {@code ServerPlayer.setGameMode} (updatePlayerAbilities + onUpdateAbilities).
     */
    public static void setGameMode(MinecraftServer server, UUID companionUuid, GameType mode) {
        if (mode == null) return;
        CompanionRegistry reg = CompanionRegistry.get(server);
        CompanionRegistry.Entry e = reg.find(companionUuid);
        if (e != null) reg.put(companionUuid, e.withGameType(mode));
        NumenPlayer body = NumenPlayer.findByUuid(server, companionUuid);
        if (body != null) body.setGameMode(mode);
    }

    /** A companion's persisted game mode (survival if unknown). */
    public static GameType getGameMode(MinecraftServer server, UUID companionUuid) {
        CompanionRegistry.Entry e = CompanionRegistry.get(server).find(companionUuid);
        return e != null ? e.gameType() : GameType.SURVIVAL;
    }

    /** Flip a companion's OP master switch, persisted in the registry. Read back via {@link #isOpEnabled}. */
    public static void setOpEnabled(MinecraftServer server, UUID companionUuid, boolean opEnabled) {
        CompanionRegistry reg = CompanionRegistry.get(server);
        CompanionRegistry.Entry e = reg.find(companionUuid);
        if (e != null) reg.put(companionUuid, e.withOpEnabled(opEnabled));
    }

    /** Whether the companion's OP master switch is on (default false — the today-equivalent, no command privileges). */
    public static boolean isOpEnabled(MinecraftServer server, UUID companionUuid) {
        CompanionRegistry.Entry e = CompanionRegistry.get(server).find(companionUuid);
        return e != null && e.opEnabled();
    }

    /** True if {@code ownerUuid} owns the companion — resolved from the live body, else the registry entry. */
    public static boolean isOwner(MinecraftServer server, UUID companionUuid, UUID ownerUuid) {
        NumenPlayer live = NumenPlayer.findByUuid(server, companionUuid);
        if (live != null) return live.isOwnedByPlayer(ownerUuid);
        CompanionRegistry.Entry e = CompanionRegistry.get(server).find(companionUuid);
        return e != null && e.owner().equals(ownerUuid);
    }

    /**
     * Does {@code ownerUuid} own a companion called {@code name}? Checks live bodies first, then the
     * registry — so it still answers correctly while the companion is dormant.
     *
     * <p>Exists for the name-keyed gates: {@link #isOwner} needs a companion UUID, but a caller
     * working from a name alone (the {@code /numenperm} tier command) has none, and "can't resolve
     * it, so allow the write" is not an acceptable fallback for an ownership check. Case-insensitive,
     * matching how the commands resolve companion names.
     */
    public static boolean ownsCompanionNamed(MinecraftServer server, UUID ownerUuid, String name) {
        if (server == null || ownerUuid == null || name == null) return false;
        String want = name.strip();
        if (want.isEmpty()) return false;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p instanceof NumenPlayer a && a.isOwnedByPlayer(ownerUuid)
                    && a.getName().getString().equalsIgnoreCase(want)) {
                return true;
            }
        }
        for (Map.Entry<UUID, CompanionRegistry.Entry> e : CompanionRegistry.get(server).ownedBy(ownerUuid)) {
            if (e.getValue().name().equalsIgnoreCase(want)) return true;
        }
        return false;
    }

    /**
     * Push an async world {@code <event>} to the companion's brain (it runs on the owner's client).
     * {@code urgent} wakes an idle brain to react now; otherwise it rides along on the next owner turn.
     * No-op if the owner is offline (no client to receive it).
     */
    public static void emitEvent(NumenPlayer body, String xml, boolean urgent) {
        ServerPlayer owner = body.resolveOwnerPlayer();
        if (owner != null) {
            Services.NETWORK.sendToPlayer(owner, new NumenEventPayload(body.getUUID(), xml, urgent));
        }
    }

    /**
     * The companion crossed into a new dimension — on its OWN (it travels where it likes, not tied to
     * the owner). Tell its brain, ambient: it rides along on the next owner-driven turn rather than
     * spending a fresh LLM call just to note the move. Called from each loader's dimension-change hook.
     */
    public static void onDimensionChanged(NumenPlayer body) {
        String dim = body.level().dimension().identifier().toString();
        emitEvent(body, "<event kind=\"dimension_change\" to=\"" + dim + "\">你进入了 " + dim
                + "。留意这个维度的环境和危险。</event>", false);
    }

    /** Save the companion to its {@code .dat} and remove it from the world (dormancy). */
    public static void dormant(MinecraftServer server, NumenPlayer body) {
        // Refresh the respawn hint before the body leaves.
        CompanionRegistry reg = CompanionRegistry.get(server);
        CompanionRegistry.Entry prev = reg.find(body.getUUID());
        if (prev != null) {
            reg.put(body.getUUID(), new CompanionRegistry.Entry(
                    prev.name(), prev.owner(),
                    ((ServerLevel) body.level()).dimension(), body.blockPosition())
                    .withGameType(prev.gameType()).withOpEnabled(prev.opEnabled()));   // keep caps across dormancy
        }
        CompanionFactory.despawn(server, body);
    }

    /** Permanently forget a companion (death / dismissal): despawn + drop the index entry. */
    public static void dismiss(MinecraftServer server, NumenPlayer body) {
        UUID uuid = body.getUUID();
        CompanionFactory.despawn(server, body);
        CompanionRegistry.get(server).remove(uuid);
    }

    /**
     * Permanently dismiss EVERY companion of {@code ownerUuid} named {@code name} — gone for good, it
     * will NOT come back on login. Removes both live bodies and registry entries, so it also cleans up
     * any same-name duplicates that the old non-idempotent summon left behind. Returns how many it
     * dismissed. (The {@code .dat} files orphan harmlessly — with no registry entry nothing respawns
     * them.)
     */
    public static int dismissByName(MinecraftServer server, UUID ownerUuid, String name) {
        CompanionRegistry reg = CompanionRegistry.get(server);
        List<UUID> ids = new ArrayList<>();
        for (Map.Entry<UUID, CompanionRegistry.Entry> e : reg.ownedBy(ownerUuid)) {
            if (e.getValue().name().equals(name)) ids.add(e.getKey());
        }
        // Defensive: also catch a live body of that name somehow missing from the registry.
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p instanceof NumenPlayer a && a.isOwnedByPlayer(ownerUuid)
                    && a.getName().getString().equals(name) && !ids.contains(a.getUUID())) {
                ids.add(a.getUUID());
            }
        }
        for (UUID id : ids) {
            NumenPlayer live = NumenPlayer.findByUuid(server, id);
            if (live != null) CompanionFactory.despawn(server, live);
            reg.remove(id);
        }
        return ids.size();
    }
}
