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

    /** Maximum accepted companion name length (trimmed), enforced inside {@link #summon} itself — NOT
     *  the same thing as {@link com.dwinovo.numen.network.payload.SummonRequestPayload#MAX_NAME} (32,
     *  the wire codec's DECODE bound, deliberately left wide; see {@link #summon}'s javadoc for why).
     *  This is the REAL limit: it matches vanilla's {@code ByteBufCodecs.PLAYER_NAME = stringUtf8(16)},
     *  which is what actually encodes {@code GameProfile.name()} every time this companion's body joins
     *  the player list in the {@code ADD_PLAYER} broadcast — {@code Utf8String.write} throws past 16. */
    public static final int MAX_NAME_LENGTH = 16;

    /** Typed outcome of {@link #summon} — see each constant's own javadoc for what it means and what a
     *  caller should tell the owner. Exists so the panel, the {@code /numen player summon} command and
     *  the payload handler all report the SAME reason for the SAME situation, instead of three
     *  independently-worded (or, before this, silently swallowed) messages. */
    public enum SummonResult {
        /** A brand-new companion was created. */
        OK,
        /** {@code name} (case-insensitively, trimmed) already named a companion the SAME owner already
         *  had — live or dormant — and it was returned/woken rather than duplicated. Not a failure; the
         *  summon contract has always been idempotent per (owner, name). */
        REUSED_EXISTING,
        /** {@code name} (case-insensitively, trimmed) already names a DIFFERENT owner's companion.
         *  Refused outright — see {@link #summon}'s javadoc for why uniqueness must be global, not
         *  per-owner. */
        NAME_TAKEN,
        /** {@code name} is blank once trimmed. */
        NAME_INVALID,
        /** {@code name}, trimmed, is longer than {@link #MAX_NAME_LENGTH} characters. */
        NAME_TOO_LONG
    }

    /** {@link #summon}'s full result: which {@link SummonResult} occurred, the resulting body (non-null
     *  iff {@link #success()}), and a ready-to-show {@code reason} (empty string on success). */
    public record SummonOutcome(SummonResult result, NumenPlayer body, String reason) {
        public boolean success() { return body != null; }
    }

    /**
     * Summon the owner's companion called {@code name}. IDEMPOTENT per (owner, name): if one already
     * exists it is reused — already live → returned as-is; dormant → brought back. Only a name with no
     * existing companion mints a fresh one. (The old "fresh random UUID every summon" minted same-name
     * duplicates that all respawned on login — that's the duplicate-companion bug.)
     *
     * <p>THE single convergence point for BOTH entry points ({@code /numen player summon} and the
     * panel's {@code SummonRequestPayload}) — every summon-time rule lives HERE so both report the
     * identical {@link SummonResult}/reason for the identical situation, and so a modified client that
     * skips its own UI validation gains nothing: this is the authority, the UI is only a convenience.
     *
     * <h2>Name length — 16, enforced here, not on the wire</h2>
     * {@code SummonRequestPayload.MAX_NAME} stays 32 on the wire deliberately: dropping the codec's
     * DECODE bound to 16 would let a 17-31 character name trip {@code ByteBufCodecs.stringUtf8} before
     * {@code handle()} even runs — a raw {@code DecoderException}, which is a WORSE failure mode than
     * the one this method exists to prevent (no clean reason, just a decode error on the connection).
     * The real cap is {@link #MAX_NAME_LENGTH} = 16, checked here, because vanilla's own
     * {@code ByteBufCodecs.PLAYER_NAME = stringUtf8(16)} is what actually encodes the resulting
     * {@code GameProfile.name()} in the {@code ADD_PLAYER} broadcast every time this companion joins the
     * player list (every spawn, not just creation) — a 17-32 character name summoned under the old,
     * unchecked cap would eventually crash broadcasting the player-info add, at some later, unrelated-
     * looking moment. Rejecting it HERE with a typed result replaces that latent crash with an ordinary,
     * immediate "name too long" response.
     *
     * <h2>Uniqueness — GLOBAL, case-insensitive, not per-owner</h2>
     * The companion name is already a global key in four OTHER places, so per-owner uniqueness would
     * close none of them: {@code CompanionPermissions} keys permission tiers on the lower-cased name for
     * the WHOLE server, and its write gate ({@code NumenPermCommand} → {@link #ownsCompanionNamed})
     * passes for anyone who owns ANY companion of that name — so without this check, Mallory could
     * summon his own "Bob" (nothing stopped him before this method) and run {@code /numenperm Bob
     * owners}, and the single global row {@code bob → owners} would raise the permission ceiling on
     * Alice's separately-owned "Bob" too. {@code DeathTolls} shares the same global name key;
     * {@code NumenSkins} caches skins by lower-cased name; and the MCP bridge's {@code resolveCompanion}
     * falls back to a case-insensitive name match, so two case-variant "Bob"/"bob" companions would let
     * an external agent acquire control of one body and invoke tool calls on the other. Comparison below
     * is {@code equalsIgnoreCase} on the TRIMMED name, matching how those four stores already normalise.
     *
     * <p><b>Known gap (not closed by this method):</b> the collision scan below covers every currently
     * LIVE companion (ANY owner, online or not — a spawned companion is a standing {@link ServerPlayer}
     * independent of its owner's own connection, found via {@code server.getPlayerList()}) plus the
     * SUMMONING owner's own DORMANT entries. It can NOT see a dormant companion belonging to a
     * DIFFERENT owner: nothing in {@link CompanionRegistry}'s public API enumerates every entry across
     * every owner (only {@code ownedBy(ownerUuid)} and {@code pendingDead()} exist), and adding a full
     * enumerator there is outside this pass's assigned file set. In practice the window is narrow — a
     * companion is dormant only right after server start, before its owner's first login this session,
     * or mid-death-respawn-delay — but it is real; closing it needs a {@code CompanionRegistry}
     * cross-owner accessor in a later pass.
     *
     * <h2>Grandfathering</h2>
     * Enforcement is summon-time only, on PURPOSE: a pre-existing duplicate or over-length name already
     * sitting in the registry is never renamed, merged or deleted by this method — only a NEW summon of
     * a colliding or oversize name is refused. A retroactive migration would destroy data irreversibly
     * and is explicitly out of scope.
     */
    public static SummonOutcome summon(MinecraftServer server, UUID ownerUuid, String name,
                                        ServerLevel level, Vec3 pos) {
        // strip(), not trim(): matches ownsCompanionNamed below and CompanionPermissions.key() in
        // mod/ — Unicode-aware whitespace trimming, not just ASCII <= U+0020, so normalisation agrees
        // across every name-keyed store this companion's name touches.
        String trimmed = name == null ? "" : name.strip();
        if (trimmed.isEmpty()) {
            return outcome(SummonResult.NAME_INVALID, null, trimmed);
        }
        if (trimmed.length() > MAX_NAME_LENGTH) {
            return outcome(SummonResult.NAME_TOO_LONG, null, trimmed);
        }

        // Global (any owner) scan of LIVE bodies. Own-name match wins immediately and short-circuits —
        // grandfathering (see javadoc above) means reaching the OWNER'S OWN pre-existing companion must
        // never be blocked by a same-named collision that already exists elsewhere, even a legacy one
        // this pass's global-uniqueness rule would refuse if someone tried to create it FRESH today.
        // Any OTHER owner's live match is remembered but not acted on yet — it only matters once we know
        // this isn't secretly the summoning owner's own companion (checked next, live AND dormant).
        NumenPlayer ownLive = null;
        boolean otherOwnerLiveMatch = false;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p instanceof NumenPlayer a && a.getName().getString().equalsIgnoreCase(trimmed)) {
                if (a.isOwnedByPlayer(ownerUuid)) {
                    ownLive = a;
                    break;
                }
                otherOwnerLiveMatch = true;
            }
        }
        if (ownLive != null) {
            return outcome(SummonResult.REUSED_EXISTING, ownLive, trimmed);
        }

        // Not live under that name — check the OWNER'S OWN dormant entries (case-insensitive, so typing
        // "bob" wakes an entry stored as "Bob"). Cross-owner DORMANT collisions are the known gap
        // documented above; this only ever looks at ownerUuid's own entries.
        UUID existing = findOwnDormantByName(server, ownerUuid, trimmed);
        if (existing != null) {
            NumenPlayer body = respawn(server, existing);
            if (body != null) {
                // A re-summon during the death window is an explicit owner request that supersedes the
                // timed respawn: without this, CompanionRegistry still shows diedAt > 0 after the respawn
                // above, so Companions.tickRespawns' pendingDead() sweep keeps this UUID queued and later
                // calls respawnDead → CompanionFactory.spawn a SECOND time for the body we just returned
                // (two ticking bodies, one identity — vanilla's PlayerList de-dupes by neither name nor UUID).
                CompanionRegistry.Entry e = CompanionRegistry.get(server).find(existing);
                if (e != null && e.diedAt() > 0L) {
                    CompanionRegistry.get(server).markAlive(existing);
                    respawnHoldUntil.remove(existing);
                }
                return outcome(SummonResult.REUSED_EXISTING, body, trimmed);
            }
            CompanionRegistry.get(server).remove(existing);   // stale entry (no .dat) — name is free again
        }

        // Genuinely not an existing companion of the SUMMONING owner's — only NOW does another owner's
        // live companion under this name block creation of a brand-new one.
        if (otherOwnerLiveMatch) {
            return outcome(SummonResult.NAME_TAKEN, null, trimmed);
        }

        UUID companionUuid = UUID.randomUUID();
        NumenPlayer body = CompanionFactory.spawn(server, companionUuid, trimmed, ownerUuid, level, pos);
        CompanionRegistry.get(server).put(companionUuid,
                new CompanionRegistry.Entry(trimmed, ownerUuid, level.dimension(), body.blockPosition()));
        return outcome(SummonResult.OK, body, trimmed);
    }

    /** Companion UUID of {@code ownerUuid}'s own DORMANT (not currently spawned) companion whose stored
     *  name matches {@code trimmedName} case-insensitively, or null. Live matches — for ANY owner — are
     *  handled by the scan in {@link #summon} before this is ever reached. */
    private static UUID findOwnDormantByName(MinecraftServer server, UUID ownerUuid, String trimmedName) {
        for (Map.Entry<UUID, CompanionRegistry.Entry> e : CompanionRegistry.get(server).ownedBy(ownerUuid)) {
            if (e.getValue().name().equalsIgnoreCase(trimmedName)) return e.getKey();
        }
        return null;
    }

    /** Builds the {@link SummonOutcome} for {@code result}, filling in {@link #reasonFor} for anything
     *  that isn't a success. Centralising this — rather than each return site in {@link #summon} building
     *  its own string — is what guarantees the command, the payload handler and (once a later wave wires
     *  {@code SummonResultPayload}) the panel all say the exact same sentence for the exact same
     *  {@link SummonResult}. */
    private static SummonOutcome outcome(SummonResult result, NumenPlayer body, String attemptedName) {
        return new SummonOutcome(result, body, reasonFor(result, attemptedName));
    }

    /** Human-readable explanation for a {@link SummonResult}, or {@code ""} for a success
     *  ({@link SummonResult#OK}/{@link SummonResult#REUSED_EXISTING}) — there's nothing to explain about
     *  those. */
    private static String reasonFor(SummonResult result, String attemptedName) {
        return switch (result) {
            case NAME_INVALID -> "Companion name can't be blank.";
            case NAME_TOO_LONG -> "Companion name '" + attemptedName + "' is too long (max "
                    + MAX_NAME_LENGTH + " characters).";
            case NAME_TAKEN -> "The name '" + attemptedName + "' is already in use by another companion.";
            case OK, REUSED_EXISTING -> "";
        };
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
        NumenPlayer body = CompanionFactory.spawn(server, companionUuid, entry.name(), entry.owner(), level, null);
        // Symmetry with summon(): this method has its own direct caller (ExecuteToolPayload, acting on a
        // companion UUID without going through summon's name lookup), so the death-state clear can't live
        // in summon() alone. Same reasoning as there — a respawn here means the entry is no longer pending,
        // so the timed sweep in tickRespawns must not spawn a second body for it later.
        if (entry.diedAt() > 0L) {
            CompanionRegistry.get(server).markAlive(companionUuid);
            respawnHoldUntil.remove(companionUuid);
        }
        return body;
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
            // W6 (control-authority, NOT a release): death does not release a live external lease — the
            // holder keeps the body across death/respawn exactly like the pre-existing in-flight-task
            // resolution does (NumenDeathPayload's freeze path), so a brain that was mid-mission does not
            // lose the companion to a stray death. Push a resend so the console/holder LEARN about the
            // death promptly via the state channel rather than waiting for the 20-tick keep-alive.
            ControlRegistry.syncToOwner(server, owner);
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
        // W6: same reasoning as onDeath — the lease (if any) was retained across death and is still
        // live now that the body is back; resend so the console/holder see the companion again promptly.
        ControlRegistry.syncToOwner(server, owner);
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
        // W6 item 7 (decision, not an oversight): game mode is the human OWNER's own switch and stays
        // deliberately UNGATED by control — the owner outranks any brain, including whichever one
        // currently holds the lease, so this never consults ControlRegistry.authorize. It must still
        // push a control-state resend so the console stays accurate (the lease itself is untouched).
        syncControlToOwnerOf(server, companionUuid);
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
        // W6 item 7 (decision, not an oversight) — same reasoning as setGameMode above: the OP master
        // switch is the human owner's own control, deliberately ungated, but still worth a prompt resend.
        syncControlToOwnerOf(server, companionUuid);
    }

    /**
     * Resolve {@code companionUuid}'s owner (live body first, else the registry entry — same fallback
     * order as {@link #isOwner}) and push a {@link ControlRegistry#syncToOwner} resend if that owner is
     * online. Shared by {@link #setGameMode} and {@link #setOpEnabled}: both are the owner's OWN
     * switches and intentionally bypass control, but a bypass must still be visible on the console
     * promptly rather than waiting for {@code ControlRegistry.tick}'s 20-tick keep-alive. Silently a
     * no-op if the companion or its owner can't be resolved (mirrors {@code syncRosterToOwner}'s own
     * null-owner guard) — there's nothing to push to.
     */
    private static void syncControlToOwnerOf(MinecraftServer server, UUID companionUuid) {
        NumenPlayer live = NumenPlayer.findByUuid(server, companionUuid);
        UUID ownerUuid = live != null ? live.getOwnerUuid() : null;
        if (ownerUuid == null) {
            CompanionRegistry.Entry e = CompanionRegistry.get(server).find(companionUuid);
            ownerUuid = e != null ? e.owner() : null;
        }
        if (ownerUuid == null) return;
        ServerPlayer owner = server.getPlayerList().getPlayer(ownerUuid);
        if (owner != null) ControlRegistry.syncToOwner(server, owner);
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
        // W6 item 5: a permanently dismissed companion has no control state left to hold. force-release
        // BEFORE forget — forceRelease runs the handover hook (drains any live external lease's queue
        // with an honest cause and notifies the console) while the record still exists; forget() alone
        // would silently drop a live holder's lease with no notification at all. No-op when nothing is
        // leased (the common case), so this is always safe to call unconditionally.
        ControlRegistry.forceRelease(server, uuid, "companion dismissed");
        ControlRegistry.forget(uuid);
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
            // W6 item 5 — same reasoning as dismiss() above: force-release before forget so a live
            // external lease's handover hook still runs (drains its queue, notifies the console) before
            // the record disappears for good.
            ControlRegistry.forceRelease(server, id, "companion dismissed");
            ControlRegistry.forget(id);
            reg.remove(id);
        }
        return ids.size();
    }
}
