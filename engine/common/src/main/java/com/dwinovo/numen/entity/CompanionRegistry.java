package com.dwinovo.numen.entity;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persistent index of every companion that exists, keyed by companion UUID.
 * The companion BODY (inventory, position, owner) persists for free as a vanilla
 * player {@code .dat}, but vanilla never enumerates the {@code playerdata/}
 * folder for players that aren't logging in — so without this index we couldn't
 * know which companions to recreate, or who owns them, while they sit dormant.
 *
 * <p>World-saved on the server-wide data storage (one file, all owners' companions).
 * The {@code dimension}/{@code pos} are a respawn hint (which level to construct
 * the body in); the {@code .dat} carries the authoritative restored state.
 */
@com.dwinovo.numen.api.Internal
public final class CompanionRegistry extends SavedData {

    /** One companion's catalog entry. {@code diedAt > 0} = dead, awaiting a respawn-at-owner (the death
     *  state is persisted here so it SURVIVES a logout during the respawn window — see Companions). */
    public record Entry(String name, UUID owner, ResourceKey<Level> dimension, BlockPos pos,
                        String deathCause, long diedAt, GameType gameType, boolean opEnabled) {
        /** A live companion (not dead): survival + OP-off — today's default for a fresh summon. */
        public Entry(String name, UUID owner, ResourceKey<Level> dimension, BlockPos pos) {
            this(name, owner, dimension, pos, "", 0L, GameType.SURVIVAL, false);
        }

        /** This entry with a different per-companion game mode (survival/creative); all else unchanged. */
        public Entry withGameType(GameType mode) {
            return new Entry(name, owner, dimension, pos, deathCause, diedAt, mode, opEnabled);
        }

        /** This entry with the per-companion OP master switch flipped; all else unchanged. */
        public Entry withOpEnabled(boolean op) {
            return new Entry(name, owner, dimension, pos, deathCause, diedAt, gameType, op);
        }

        static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("name").forGetter(Entry::name),
                UUIDUtil.STRING_CODEC.fieldOf("owner").forGetter(Entry::owner),
                ResourceKey.codec(Registries.DIMENSION).fieldOf("dimension").forGetter(Entry::dimension),
                BlockPos.CODEC.fieldOf("pos").forGetter(Entry::pos),
                Codec.STRING.optionalFieldOf("deathCause", "").forGetter(Entry::deathCause),
                Codec.LONG.optionalFieldOf("diedAt", 0L).forGetter(Entry::diedAt),
                // Per-companion capabilities. Optional + defaulted so a pre-feature companions.dat reads as
                // survival + OP-off — no migration needed, no disruption to existing companions.
                GameType.CODEC.optionalFieldOf("gameType", GameType.SURVIVAL).forGetter(Entry::gameType),
                Codec.BOOL.optionalFieldOf("opEnabled", false).forGetter(Entry::opEnabled)
        ).apply(i, Entry::new));
    }

    private static final Codec<CompanionRegistry> CODEC =
            Codec.unboundedMap(UUIDUtil.STRING_CODEC, Entry.CODEC)
                    .xmap(CompanionRegistry::new, d -> d.entries)
                    .fieldOf("companions").codec();

    // 1.21.5 codec-based SavedDataType: name + supplier + CODEC + datafix type. The storage layer
    // drives (de)serialization through CODEC, so no save()/load() overrides are needed.
    private static final SavedDataType<CompanionRegistry> TYPE = new SavedDataType<>(
            net.minecraft.resources.Identifier.fromNamespaceAndPath("numen", "companions"),
            CompanionRegistry::new, CODEC,
            net.minecraft.util.datafix.DataFixTypes.SAVED_DATA_RANDOM_SEQUENCES);

    private final Map<UUID, Entry> entries;

    private CompanionRegistry() {
        this.entries = new HashMap<>();
    }

    private CompanionRegistry(Map<UUID, Entry> entries) {
        this.entries = new HashMap<>(entries);
    }

    public static CompanionRegistry get(MinecraftServer server) {
        // Server-wide (not per-dimension) storage: this index spans every dimension/owner, so it
        // belongs on the server data storage — the vanilla 26.1.2 home for server-global SavedData
        // (scoreboard, maps, clock, weather, game rules). That lands the file at the world-root
        // data/numen/companions.dat. (1.21.x had no server-wide storage, so the old idiom parked
        // server-global data on overworld().getDataStorage(); in 26.1.2 the overworld moved under
        // dimensions/minecraft/overworld/, which is where that idiom silently wrote the file.)
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    /** Add or update a companion's catalog entry. */
    public void put(UUID companionUuid, Entry entry) {
        entries.put(companionUuid, entry);
        setDirty();
    }

    public Entry find(UUID companionUuid) {
        return entries.get(companionUuid);
    }

    public void remove(UUID companionUuid) {
        if (entries.remove(companionUuid) != null) setDirty();
    }

    /** Every companion owned by {@code ownerUuid} (UUID + entry). */
    public List<Map.Entry<UUID, Entry>> ownedBy(UUID ownerUuid) {
        List<Map.Entry<UUID, Entry>> out = new ArrayList<>();
        for (Map.Entry<UUID, Entry> e : entries.entrySet()) {
            if (e.getValue().owner().equals(ownerUuid)) out.add(e);
        }
        return out;
    }

    /** Every companion currently dead and awaiting respawn (persisted, survives a logout). */
    public List<Map.Entry<UUID, Entry>> pendingDead() {
        List<Map.Entry<UUID, Entry>> out = new ArrayList<>();
        for (Map.Entry<UUID, Entry> e : entries.entrySet()) {
            if (e.getValue().diedAt() > 0L) out.add(e);
        }
        return out;
    }

    /** Mark a companion dead (records the cause + game-time, persisted for the respawn timer). */
    public void markDead(UUID uuid, String cause, long diedAt) {
        Entry e = entries.get(uuid);
        if (e == null) return;
        entries.put(uuid, new Entry(e.name(), e.owner(), e.dimension(), e.pos(), cause, diedAt,
                e.gameType(), e.opEnabled()));   // preserve per-companion caps across the death state
        setDirty();
    }

    /** Clear the death state (called when the body is respawned). */
    public void markAlive(UUID uuid) {
        Entry e = entries.get(uuid);
        if (e == null || e.diedAt() == 0L) return;
        entries.put(uuid, new Entry(e.name(), e.owner(), e.dimension(), e.pos(), "", 0L,
                e.gameType(), e.opEnabled()));   // clear death, keep game mode + OP
        setDirty();
    }
}
