package com.dwinovo.numen.entity;

import com.dwinovo.numen.Constants;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.DismountHelper;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;

import java.util.Set;
import java.util.UUID;

/**
 * Spawns and despawns companion {@link NumenPlayer} bodies through the vanilla
 * player-join path — entirely public API, no loader-specific construction needed
 * (the only fake piece is {@link FakeConnection}, which is common).
 *
 * <p>{@link net.minecraft.server.players.PlayerList#placeNewPlayer} adds the body
 * to the player list (→ chunk loading for free) and to the level, but does NOT
 * load a hand-built fake player's {@code .dat} (that path is tied to the real
 * login flow) — so {@link #spawn} restores position / inventory / owner from disk
 * explicitly afterwards, the way Carpet's {@code EntityPlayerMPFake} does.
 * {@link net.minecraft.server.players.PlayerList#remove} saves that data back and
 * removes the body — so despawn is a clean, persisted dormancy.
 */
@com.dwinovo.numen.api.Internal
public final class CompanionFactory {

    private CompanionFactory() {}

    /**
     * Bring a companion into the world. On first creation pass a {@code pos}
     * (the spawn location, e.g. beside the owner); on a respawn from dormancy
     * pass {@code null} to keep the position restored from its {@code .dat}.
     */
    public static NumenPlayer spawn(MinecraftServer server, UUID companionUuid, String name,
                                     UUID ownerUuid, ServerLevel level, Vec3 pos) {
        GameProfile profile = new GameProfile(companionUuid, name);
        NumenPlayer player = new NumenPlayer(server, level, profile, ClientInformation.createDefault());
        FakeConnection connection = new FakeConnection();
        server.getPlayerList().placeNewPlayer(connection, player,
                CommonListenerCookie.createInitial(profile, false));
        // placeNewPlayer does NOT load a hand-built fake player's .dat, so restore
        // it ourselves (Carpet's model): position, inventory, health, owner from
        // disk. Without this a respawned companion spawns at 0,0,0 with no items.
        loadPlayerData(server, player);
        // Companions are always survival, whatever the world's default game type — their whole design
        // (gather/drops, real combat, recoverable death) is survival-shaped, and placeNewPlayer would
        // otherwise hand a creative world's body instabuild (no block drops, breaks auto_mine). Forced
        // here after the .dat restore so a stale saved game type can't override it.
        player.setGameMode(GameType.SURVIVAL);
        // First spawn has no .dat to restore the owner from; set it explicitly.
        if (player.getOwnerUuid() == null) {
            player.setOwnerUuid(ownerUuid);
        }
        // An explicit pos (fresh summon) overrides the restored position; a respawn
        // from dormancy passes null to keep exactly what the .dat restored.
        if (pos != null) {
            player.teleportTo(level, pos.x, pos.y, pos.z, Set.of(), player.getYRot(), player.getXRot(), false);
        }
        // A real player login gets vanilla safety placement; this hand-built spawn path skips it. While the
        // companion was dormant the world may have shifted (a build placed over its .dat spot), leaving the
        // body embedded in solid blocks — it would tick once and take IN_WALL (suffocation) damage from
        // spawn. Nudge it to a fitting spot BEFORE it ticks. Runs on the final position, so it covers all
        // callers: dormancy restore (pos == null), fresh summon, and death-respawn-at-owner (pos != null).
        nudgeOutOfWall(level, player);
        return player;
    }

    /** Horizontal reach of the escape spiral, in blocks (task cap: radius ≤ 8). */
    private static final int SEARCH_RADIUS = 8;
    /** How far straight up the "buried under a fresh build" scan reaches, in blocks. */
    private static final int VERTICAL_REACH = 8;
    /** dy scan order for a spiral cell: same Y first, then ±1..±4 (task: "prefer same Y ± 4"). */
    private static final int[] DY_PREFERENCE = {0, -1, 1, -2, 2, -3, 3, -4, 4};

    /**
     * If the body's bounding box at its current position intersects solid/suffocating blocks, teleport it to
     * the nearest spot where a player fits with solid ground below, BEFORE it ticks and suffocates. Mirrors
     * vanilla respawn placement ({@link net.minecraft.world.level.block.RespawnAnchorBlock#findStandUpPosition}
     * built on {@link DismountHelper#findSafeDismountLocation}). No-op when the position is already clear, so
     * a normal login / summon is untouched.
     */
    private static void nudgeOutOfWall(ServerLevel level, NumenPlayer player) {
        if (level.noCollision(player) && !player.isInWall()) {
            return;   // fits and its head is clear — normal case, keep the position exactly as restored
        }
        Vec3 from = player.position();
        Vec3 safe = findSafePosition(level, player);
        if (safe == null) {
            // Nothing fit within range — drop onto the surface column at the same x/z. The heightmap gives
            // the y a player stands on top of the terrain, which is always open (non-suffocating).
            BlockPos bp = player.blockPosition();
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bp.getX(), bp.getZ());
            safe = new Vec3(bp.getX() + 0.5, y, bp.getZ() + 0.5);
        }
        player.teleportTo(level, safe.x, safe.y, safe.z, Set.of(), player.getYRot(), player.getXRot(), false);
        Constants.LOG.info("[numen-spawn] restored position was unsafe, nudged {} → {}", fmt(from), fmt(safe));
    }

    /**
     * Nearest position where a player-sized body fits with valid ground below: straight up first (the common
     * "buried by a few blocks" case), then an outward horizontal spiral (radius ≤ {@value #SEARCH_RADIUS},
     * preferring the same Y ± 4). Two passes like vanilla {@code findStandUpPosition}: reject dangerous
     * blocks (lava / fire / magma / cactus …) first, then accept them rather than fail. Null if nothing fits.
     */
    private static Vec3 findSafePosition(ServerLevel level, NumenPlayer player) {
        EntityType<?> type = player.getType();
        BlockPos origin = player.blockPosition();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (boolean checkDangerous : new boolean[]{true, false}) {
            // 1) straight up from the restored block — resolves a body buried under a fresh build fastest.
            for (int dy = 0; dy <= VERTICAL_REACH; dy++) {
                cursor.set(origin.getX(), origin.getY() + dy, origin.getZ());
                Vec3 hit = DismountHelper.findSafeDismountLocation(type, level, cursor, checkDangerous);
                if (hit != null) return hit;
            }
            // 2) horizontal spiral outward, each ring scanned at the preferred Y offsets first.
            for (int r = 1; r <= SEARCH_RADIUS; r++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;   // ring perimeter only
                        for (int dy : DY_PREFERENCE) {
                            cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                            Vec3 hit = DismountHelper.findSafeDismountLocation(type, level, cursor, checkDangerous);
                            if (hit != null) return hit;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static String fmt(Vec3 v) {
        return String.format("(%.1f, %.1f, %.1f)", v.x, v.y, v.z);
    }

    /**
     * Restore a fake player's saved state from its playerdata {@code .dat}
     * ({@link net.minecraft.server.players.PlayerList#loadPlayerData} +
     * {@link net.minecraft.world.entity.Entity#load}). {@code placeNewPlayer}
     * skips this for hand-constructed players, so we do it like Carpet's
     * {@code loadPlayerData}. No-op on first summon (no file yet).
     */
    private static void loadPlayerData(MinecraftServer server, NumenPlayer player) {
        // 1.21.10: load(player, reporter) is gone; loadPlayerData(nameAndId) returns the raw
        // CompoundTag, which we wrap into a ValueInput via TagValueInput.create for Entity.load.
        server.getPlayerList().loadPlayerData(player.nameAndId())
                .map(tag -> TagValueInput.create(ProblemReporter.DISCARDING, player.registryAccess(), tag))
                .ifPresent(player::load);
    }

    /** Save the companion's data and remove it from the world (dormancy). */
    public static void despawn(MinecraftServer server, NumenPlayer player) {
        // Tell tool packs the body is leaving so they can finalize their own
        // per-companion work (e.g. clear a mining crack overlay) instead of leaving
        // it orphaned once the body drops out of the tick loop's player list.
        CompanionLifecycle.fireRemove(player);
        server.getPlayerList().remove(player);
    }
}
