package com.dwinovo.numen.client.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Client-side registry of {@link EntityAgentLoop} instances — one per Numen
 * the player is talking to, keyed by the stable {@code entity.getUUID()}.
 *
 * <h2>Why UUID, not network id</h2>
 * The vanilla network {@code entity.getId()} (int) is a per-session handle that
 * changes whenever the entity is recreated — including every cross-dimension
 * trip, where a non-player entity is destroyed and rebuilt with a fresh int id
 * but the same UUID. Keying the loop (and its conversation state) by UUID is
 * what lets the agent survive a Nether/End traversal: the rebuilt body resolves
 * back to the same loop via {@link ClientNumenLookup}. The int id is only ever
 * used as an ephemeral handle for the current tick.
 *
 * <h2>Single-layer architecture</h2>
 * Each Numen carries its own conversation; the owner chats with each entity
 * directly. There is no coordinating brain — see {@link EntityAgentLoop}.
 *
 * <h2>Threading</h2>
 * Client main thread only — every entry point (payload handler, chat screen,
 * interact handler) runs on it. No locks.
 */
public final class AgentLoopRegistry {

    private static final Map<UUID, EntityAgentLoop> ENTITY_LOOPS = new HashMap<>();

    private AgentLoopRegistry() {}

    /** Create-on-first-access. The returned loop is bound to {@code entityUuid} for its lifetime. */
    public static EntityAgentLoop getOrCreate(UUID entityUuid) {
        return ENTITY_LOOPS.computeIfAbsent(entityUuid, EntityAgentLoop::new);
    }

    /** Read-only lookup; never creates. Used by the S→C result handler. */
    public static Optional<EntityAgentLoop> get(UUID entityUuid) {
        return Optional.ofNullable(ENTITY_LOOPS.get(entityUuid));
    }

    /**
     * §7 INFERENCE seam, pack-facing entry point. A tool pack's {@code commit_inference}
     * client-local tool calls this with the companion identity it already has on the call
     * ({@code call.ctx().entityUuid()}) — mirroring how {@code loadSkill} reaches engine
     * state through a static entry point. Appends
     * {@code <inference provenance="inferred">text</inference>} to that companion's
     * conversation tail (seq/schemaVersion/gameTime-stamped by the §3 envelope choke
     * point) and returns the tool-result confirmation JSON. Fails soft (JSON with
     * {@code success:false}) when no loop exists for the uuid. Client main thread only.
     */
    public static String commitInference(UUID entityUuid, String text) {
        EntityAgentLoop loop = ENTITY_LOOPS.get(entityUuid);
        if (loop == null) {
            return "{\"success\":false,\"message\":\"no agent loop for entity " + entityUuid + "\"}";
        }
        return loop.commitInference(text);
    }

    /**
     * Wave D landmark-naming seam, pack-facing entry point (mirrors {@link #commitInference}). The pack's
     * {@code remember_place} client-local tool calls this with the companion identity on its call
     * ({@code call.ctx().entityUuid()}); the loop names / annotates the landmark and emits the resulting
     * {@code added} / {@code renamed} event through the existing landmark machinery. Null {@code x/y/z} name
     * the companion's current position. Fails soft (JSON {@code success:false}) when no loop exists. Client
     * main thread only.
     */
    public static String rememberPlace(UUID entityUuid, Integer x, Integer y, Integer z,
                                       String label, String category, String note) {
        EntityAgentLoop loop = ENTITY_LOOPS.get(entityUuid);
        if (loop == null) {
            return "{\"success\":false,\"message\":\"no agent loop for entity " + entityUuid + "\"}";
        }
        return loop.rememberPlace(x, y, z, label, category, note);
    }

    /** Wave D {@code forget_place} seam — remove a landmark by id or label. Fails soft when no loop exists. */
    public static String forgetPlace(UUID entityUuid, String idOrLabel) {
        EntityAgentLoop loop = ENTITY_LOOPS.get(entityUuid);
        if (loop == null) {
            return "{\"success\":false,\"message\":\"no agent loop for entity " + entityUuid + "\"}";
        }
        return loop.forgetPlace(idOrLabel);
    }

    /** Wave D {@code recall_places} seam — the current landmark list as text. Fails soft when no loop exists. */
    public static String listPlaces(UUID entityUuid) {
        EntityAgentLoop loop = ENTITY_LOOPS.get(entityUuid);
        if (loop == null) {
            return "(无法回忆:未找到该同伴的会话循环)";
        }
        return loop.listPlaces();
    }

    /**
     * UUIDs of the companions whose loop is mid-turn ({@link EntityAgentLoop#canInterrupt()}
     * — thinking, awaiting tool results, or with a queued prompt). These are the
     * heartbeat targets: a server-side chunk-ticket lease should be held for each
     * so the body stays loaded through the owner's think-time.
     */
    public static List<UUID> activeEntityUuids() {
        List<UUID> out = new ArrayList<>();
        for (Map.Entry<UUID, EntityAgentLoop> e : ENTITY_LOOPS.entrySet()) {
            if (e.getValue().canInterrupt()) out.add(e.getKey());
        }
        return out;
    }

    /**
     * Drive every loop once per client tick — currently just the in-flight
     * tool backstop timeout. Wired from each loader's client-tick hook. Safe to
     * iterate directly: no path reached from {@code clientTick} adds or removes
     * loops.
     */
    public static void tickAll() {
        for (EntityAgentLoop loop : ENTITY_LOOPS.values()) {
            loop.clientTick();
        }
    }

    /** Drop one entity's loop (e.g. when it dies / unloads). */
    public static void dispose(UUID entityUuid) {
        EntityAgentLoop loop = ENTITY_LOOPS.remove(entityUuid);
        if (loop != null) loop.dispose();
    }

    /** Clear everything — called on world-disconnect / explicit reset. */
    public static void clear() {
        ENTITY_LOOPS.clear();
    }
}
