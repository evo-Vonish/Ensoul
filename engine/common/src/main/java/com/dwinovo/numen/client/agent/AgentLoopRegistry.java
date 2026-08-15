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

    /**
     * Drop one entity's loop (e.g. when it dies / unloads).
     *
     * <p>W8: does NOT touch control state — there is none to destroy here anymore. Control is
     * server-authoritative ({@code ControlRegistry}); dropping the client-side conversation loop
     * has no bearing on who is allowed to drive the body. A fresh loop created later for the same
     * UUID (see {@link #getOrCreate}) simply reads whatever {@link ClientControl} reports at that
     * point, same as any other loop.
     */
    public static void dispose(UUID entityUuid) {
        EntityAgentLoop loop = ENTITY_LOOPS.remove(entityUuid);
        if (loop != null) loop.dispose();
    }

    /**
     * Clear everything — called on world-disconnect / explicit reset.
     *
     * <p>Disposes each loop before dropping the map. Clearing the map alone only unhooks the
     * loops: a turn already in flight still completes, still bills the owner, still dispatches
     * its tools, and still appends to the conversation log that the replacement loop is now
     * writing. {@link #dispose(UUID)} has always done this for a single companion; the bulk
     * path silently didn't.
     *
     * <p>W8: like {@link #dispose}, this must NOT issue a release — {@code /numen reset} (the
     * command that reaches this) clears CONVERSATIONS, not leases. Deliberately no {@code
     * NumenActuator.release} call here: control is a server concept now, wholly independent of
     * whether the client happens to have a loop object materialised for a companion.
     */
    public static void clear() {
        for (EntityAgentLoop loop : ENTITY_LOOPS.values()) {
            loop.dispose();
        }
        ENTITY_LOOPS.clear();
    }

    /**
     * W9 — the engine half of {@link com.dwinovo.numen.api.NumenActuator#invoke}'s post-call
     * landmark harvest. External tool calls bypass the built-in dispatcher's {@code
     * ToolDispatcher.Sink} entirely (that is the whole point of a headless invoke), so without
     * this an external agent's {@code place_block}/{@code interact_at} is invisible to {@link
     * LandmarkStore} — after handback the built-in brain has no memory of it and re-crafts /
     * re-places duplicates. Routes to the SAME harvest path the Sink uses for the built-in
     * brain's own tool results ({@link EntityAgentLoop#harvestLandmarks}): records into {@code
     * LandmarkStore} and lets the existing emitter queue its {@code <landmark_event>} tail note
     * for whenever the built-in brain next runs a turn. Deliberately does NOT touch the
     * conversation — this is memory bookkeeping only, not a message a paused brain "hears".
     *
     * <p>No-op (not an error) when no loop exists yet for this companion — read-only lookup, NOT
     * {@link #getOrCreate}. A companion whose built-in brain has never been used has no {@link
     * LandmarkStore} to harvest into, and creating a loop here would reintroduce exactly the
     * disk-replay side effect W9 removes from {@code NumenActuator.acquire}. Client main thread
     * only, like every other loop entry point.
     */
    public static void harvestExternal(UUID entityUuid, String toolName, String resultJson) {
        EntityAgentLoop loop = ENTITY_LOOPS.get(entityUuid);
        if (loop != null) loop.harvestLandmarks(toolName, resultJson);
    }
}
