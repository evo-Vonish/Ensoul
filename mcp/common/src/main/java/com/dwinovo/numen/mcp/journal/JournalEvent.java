package com.dwinovo.numen.mcp.journal;

import com.google.gson.JsonObject;

import java.util.UUID;

/**
 * One entry in the {@link McpJournal} — the CESP §3 event envelope plus ICE §4.6's task tag.
 *
 * <p>This is the unit of information an external agent receives. It is deliberately the SAME
 * shape as the envelope the built-in brain's context carries, because information parity means
 * the two brains read the same events, not merely that they can call the same tools.
 *
 * <h2>Why {@code type} is a String and not an enum</h2>
 * Three reasons, in order of weight:
 * <ol>
 *   <li><b>It is a wire value.</b> Every event is serialised to JSON and read by an external
 *       process. An enum would be converted to and from a string at both ends anyway, so the
 *       enum would buy type-safety only for the few lines between construction and
 *       serialisation.</li>
 *   <li><b>The set is open by design.</b> Each build slice adds event types (S5 adds the whole
 *       world-cognition family). A closed enum makes every addition a cross-cutting edit.</li>
 *   <li><b>Enum constants cascade into exhaustive switches.</b> This codebase has already shipped
 *       a compile error exactly that way — adding {@code Head.FALLING} broke a switch expression
 *       in the enum's own file that a search for {@code Head.} could not find, because a switch
 *       beside its enum uses bare {@code case} labels. Consumers here FILTER events, they do not
 *       switch exhaustively over them, so an enum's one real benefit does not apply.</li>
 * </ol>
 * Use the {@link Type} constants rather than string literals at call sites.
 *
 * @param seq            monotonic, journal-global. The cursor addresses this. Never reused, never
 *                       reordered — "same topic, highest seq wins" (CESP §3) depends on it.
 * @param schemaVersion  present from day one even at v1, so a reader can upcast rather than guess.
 * @param gameTime       the world's tick clock when observed. Supplied by the caller: keeping the
 *                       clock out of this class is what lets the journal be tested with no
 *                       Minecraft on the classpath.
 * @param type           see {@link Type}.
 * @param provenance     {@code observed | reported | inferred | system} (CESP §3). Classification,
 *                       never a fabricated confidence number.
 * @param companion      the companion this concerns, or {@code null} for journal-wide events
 *                       (notably {@link Type#GAP}).
 * @param taskId         the dispatched task this belongs to, or {@code null}. This is the thread
 *                       that ties a placeholder to its eventual terminal event (ICE §4.3).
 * @param body           type-specific payload. Never null; use an empty object.
 */
public record JournalEvent(long seq,
                            int schemaVersion,
                            long gameTime,
                            String type,
                            String provenance,
                            UUID companion,
                            String taskId,
                            JsonObject body) {

    /** The only schema version that exists yet. Written explicitly so readers can branch later. */
    public static final int SCHEMA_VERSION = 1;

    /** Event type constants. Open set — slices add to it; nothing switches exhaustively. */
    public static final class Type {
        // ---- task lifecycle (ICE §4.3). Exactly one terminal event per dispatched task. ----
        /** A world-action tool was accepted and is running. Pairs with the placeholder. */
        public static final String TASK_DISPATCHED = "TASK_DISPATCHED";
        public static final String TASK_COMPLETED = "TASK_COMPLETED";
        public static final String TASK_FAILED = "TASK_FAILED";
        /** Pre-empted by something with a better claim on the body — carries {@code interrupted_by}. */
        public static final String TASK_INTERRUPTED = "TASK_INTERRUPTED";
        /** Withdrawn before finishing — carries {@code cancelled_by}. */
        public static final String TASK_CANCELLED = "TASK_CANCELLED";

        // ---- world cognition (S5 tees these from the built-in brain's own stream) ----
        public static final String PERCEPTION = "PERCEPTION";
        public static final String REGION_SNAPSHOT = "REGION_SNAPSHOT";
        public static final String REGION_DIFF = "REGION_DIFF";
        public static final String LANDMARK_EVENT = "LANDMARK_EVENT";
        public static final String SYSTEM_NOTICE = "SYSTEM_NOTICE";

        // ---- lifecycle + control ----
        public static final String DEATH = "DEATH";
        public static final String RESPAWN = "RESPAWN";
        public static final String CONTROL_CHANGED = "CONTROL_CHANGED";
        public static final String LEASE_LOST = "LEASE_LOST";
        /** A survival reflex took the body. Always attributed, never silent (ICE §5.3). */
        public static final String REFLEX = "REFLEX";
        public static final String USAGE = "USAGE";

        /**
         * Synthesised, never appended: told to a reader whose cursor fell off the back of the
         * ring. Honesty over silence — an agent that missed events must know it missed them,
         * the same principle as CESP's "conflicts keep both sources".
         */
        public static final String GAP = "GAP";

        private Type() {}
    }

    /** Provenance values (CESP §3). */
    public static final class Provenance {
        public static final String OBSERVED = "observed";
        public static final String REPORTED = "reported";
        public static final String INFERRED = "inferred";
        public static final String SYSTEM = "system";

        private Provenance() {}
    }

    /** Defensive normalisation: a null body is an empty object, never a null dereference later. */
    public JournalEvent {
        if (body == null) body = new JsonObject();
        if (provenance == null || provenance.isBlank()) provenance = Provenance.OBSERVED;
    }

    /** True when this event belongs to {@code candidate} — {@code null}-safe both ways. */
    public boolean concerns(UUID candidate) {
        return companion != null && companion.equals(candidate);
    }

    /**
     * The wire form. Optional fields are OMITTED rather than emitted as JSON null: an absent
     * {@code taskId} means "not task-related", and a reader should not have to distinguish that
     * from an explicit null.
     */
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("seq", seq);
        o.addProperty("schemaVersion", schemaVersion);
        o.addProperty("gameTime", gameTime);
        o.addProperty("type", type);
        o.addProperty("provenance", provenance);
        if (companion != null) o.addProperty("companion", companion.toString());
        if (taskId != null && !taskId.isBlank()) o.addProperty("taskId", taskId);
        o.add("body", body);
        return o;
    }
}
