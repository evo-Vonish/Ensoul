package com.dwinovo.numen.mcp.journal;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The append-only, cursor-addressable event log that external agents read the world through.
 *
 * <p>This class is the answer to the problem that motivated the whole ICE-MCP rebuild: an
 * external agent could not <em>continuously receive</em> information from the game. The missing
 * piece was never a transport — it was an addressable log. Given one, "tell me what happened
 * since seq N" is a single call, and the transport (long-poll now, SSE later) becomes an
 * optimisation rather than the contract.
 *
 * <h2>No Minecraft on the classpath, deliberately</h2>
 * Nothing here imports a game class; {@code gameTime} arrives as a {@code long} from the caller.
 * That is a design constraint, not an accident: sequence discipline, cursor arithmetic and gap
 * detection are the parts most worth testing, and this project's build has repeatedly proven that
 * requiring the Minecraft toolchain to run a test is a good way to never run the test.
 *
 * <h2>Ring, not list</h2>
 * Memory is bounded by construction. When the ring wraps, the oldest events are overwritten and
 * {@link #oldestSeq} advances. A reader whose cursor has fallen behind that point is told so
 * with a synthetic {@link JournalEvent.Type#GAP} — never silently handed a truncated view. An
 * agent acting on the belief that it has seen everything, when it has not, is the failure this
 * costs one class field to prevent.
 *
 * <h2>Threading</h2>
 * {@link #append} is called from the client main thread (the game tick) and MUST NOT block on
 * anything: it takes the monitor, writes one slot, bumps a counter and notifies. Readers park in
 * {@link #await} on the same monitor. No I/O, no network write, and no game call is ever made
 * while holding it — a journal that can stall the tick would be worse than no journal.
 */
public final class McpJournal {

    /** ~an hour of busy play at observed event rates; a few hundred KB of references. */
    public static final int DEFAULT_CAPACITY = 2048;

    /** Hard ceiling on one batch, whatever a caller asks for — protects the agent's context too. */
    public static final int MAX_BATCH = 256;

    /**
     * Server-side cap on how long {@link #await} may park, regardless of the caller's request.
     * Deliberately below the ~60s tool-call timeout typical of desktop agent harnesses: a
     * long-poll that outlives the harness's patience is killed client-side and looks to the agent
     * like a broken tool, which is strictly worse than an honest empty return. See ICE-MCP §5.
     */
    public static final long MAX_WAIT_MILLIS = 50_000L;

    /** Why {@link #await} returned. Wire values; {@code reason} in the tool result. */
    public static final class Reason {
        /** Ordinary new events were available (no wake condition was configured, or none matched). */
        public static final String EVENTS = "events";
        /** A task named in {@link Wake#taskIds} reached a terminal state. */
        public static final String TASK = "task";
        /** An urgent perception arrived — the second thinking-gear's delivery path. */
        public static final String URGENT = "urgent";
        /** Control state changed (takeover, release, lease lost). */
        public static final String CONTROL = "control";
        /** Nothing arrived before the deadline. An empty batch here is success, not an error. */
        public static final String TIMEOUT = "timeout";
        /** The cursor had fallen off the ring; the batch leads with a GAP event. */
        public static final String GAP = "gap";
        /** More events remain immediately; the caller should read again without waiting. */
        public static final String MAX_EVENTS = "max_events";

        private Reason() {}
    }

    /**
     * Which events a reader cares about at all. Empty set means "no restriction on this axis" —
     * an empty filter is the identity, so {@link Filter#ALL} costs nothing to pass.
     */
    public record Filter(Set<UUID> companions, Set<String> types) {

        public static final Filter ALL = new Filter(Set.of(), Set.of());

        public Filter {
            companions = companions == null ? Set.of() : Set.copyOf(companions);
            types = types == null ? Set.of() : Set.copyOf(types);
        }

        public boolean accepts(JournalEvent e) {
            // Journal-wide events (companion == null, e.g. GAP/USAGE) are never filtered out by a
            // companion restriction: they are not ABOUT another companion, they are about everyone.
            if (!companions.isEmpty() && e.companion() != null && !companions.contains(e.companion())) {
                return false;
            }
            return types.isEmpty() || types.contains(e.type());
        }
    }

    /**
     * What justifies returning EARLY, before the deadline.
     *
     * <p>The distinction matters and is the whole point of the parameter. With no wake condition,
     * any accepted event returns immediately — lowest latency, the right default for an agent
     * that has nothing else to do. With one set, ordinary events ACCUMULATE and only the named
     * task, an urgent perception, or a control change cuts the wait short. That is what lets an
     * agent say "let me keep reasoning; interrupt me only for these" — ICE's 停召 with a
     * priority filter, rather than a blunt sleep.
     */
    public record Wake(Set<String> taskIds, boolean urgent, boolean control) {

        /** No early-return conditions: any accepted event returns at once. */
        public static final Wake ANY = new Wake(Set.of(), false, false);

        public Wake {
            taskIds = taskIds == null ? Set.of() : Set.copyOf(taskIds);
        }

        /** True when this wake configuration expresses no preference — see {@link #ANY}. */
        public boolean isAny() {
            return taskIds.isEmpty() && !urgent && !control;
        }

        /** The {@link Reason} this event justifies waking for, or {@code null} if it does not. */
        public String reasonFor(JournalEvent e) {
            if (!taskIds.isEmpty() && e.taskId() != null && taskIds.contains(e.taskId())
                    && isTerminal(e.type())) {
                return Reason.TASK;
            }
            if (urgent && isUrgent(e)) return Reason.URGENT;
            if (control && isControl(e.type())) return Reason.CONTROL;
            return null;
        }

        /**
         * A task wake fires on TERMINAL states only. {@code TASK_DISPATCHED} is the agent's own
         * echo — waking on it would return the caller to its own dispatch, which is never news.
         */
        private static boolean isTerminal(String type) {
            return JournalEvent.Type.TASK_COMPLETED.equals(type)
                    || JournalEvent.Type.TASK_FAILED.equals(type)
                    || JournalEvent.Type.TASK_INTERRUPTED.equals(type)
                    || JournalEvent.Type.TASK_CANCELLED.equals(type);
        }

        /**
         * Urgency is a property of the EVENT, marked by its producer, not a keyword guess made
         * here. A reflex firing is always urgent: by definition the body was already in danger.
         */
        private static boolean isUrgent(JournalEvent e) {
            if (JournalEvent.Type.REFLEX.equals(e.type())) return true;
            if (JournalEvent.Type.DEATH.equals(e.type())) return true;
            JsonObject b = e.body();
            return b.has("urgent") && b.get("urgent").isJsonPrimitive()
                    && b.get("urgent").getAsJsonPrimitive().isBoolean()
                    && b.get("urgent").getAsBoolean();
        }

        private static boolean isControl(String type) {
            return JournalEvent.Type.CONTROL_CHANGED.equals(type)
                    || JournalEvent.Type.LEASE_LOST.equals(type);
        }
    }

    /**
     * One read's worth of journal.
     *
     * @param cursor  what the caller should pass as {@code sinceSeq} next time. Advances even for
     *                an empty timeout batch, so a caller can never accidentally rewind.
     * @param more    true when events remained beyond {@link #MAX_BATCH}; read again immediately
     *                rather than waiting.
     */
    public record Batch(List<JournalEvent> events, long cursor, String reason, boolean more) {}

    private final int capacity;
    private final JournalEvent[] ring;

    /** seq of the most recent append; 0 means nothing has ever been appended. */
    private long lastSeq = 0L;
    /** How many events have been overwritten and are gone for good. */
    private long dropped = 0L;

    public McpJournal() {
        this(DEFAULT_CAPACITY);
    }

    public McpJournal(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be >= 1");
        this.capacity = capacity;
        this.ring = new JournalEvent[capacity];
    }

    // ============================================================ write side (game thread)

    /**
     * Append one event and wake every parked reader. Assigns the seq — callers never choose it,
     * because a caller-chosen seq is a caller-chosen ordering, and ordering is the one thing this
     * class exists to own.
     *
     * <p>Returns the stored event so a caller that needs the assigned seq (the placeholder
     * protocol quotes it back to the agent as {@code dispatchedAt.seq}) does not have to read it
     * back out.
     */
    public synchronized JournalEvent append(String type, String provenance, long gameTime,
                                            UUID companion, String taskId, JsonObject body) {
        long seq = ++lastSeq;
        JournalEvent e = new JournalEvent(seq, JournalEvent.SCHEMA_VERSION, gameTime,
                type, provenance, companion, taskId, body);
        int slot = (int) ((seq - 1) % capacity);
        if (ring[slot] != null) dropped++;   // we are overwriting a live event: it is now lost
        ring[slot] = e;
        notifyAll();
        return e;
    }

    // ============================================================ read side (HTTP threads)

    /** seq of the newest event, or 0 when the journal has never been written. */
    public synchronized long lastSeq() {
        return lastSeq;
    }

    /** The oldest seq still retrievable. 1 until the ring first wraps. */
    public synchronized long oldestSeq() {
        return dropped + 1;
    }

    /**
     * Non-blocking read of everything after {@code sinceSeq}. This is {@code task_status}'s and
     * the SSE catch-up path's primitive; {@link #await} is built on it.
     */
    public synchronized Batch since(long sinceSeq, Filter filter, int maxEvents) {
        return collect(sinceSeq, filter, maxEvents, null);
    }

    /**
     * Block until something worth returning exists, or the deadline passes.
     *
     * <p>Returns immediately if events are already pending — a caller that has fallen behind is
     * never made to wait for news it already has. Otherwise parks on this journal's monitor,
     * re-checking on every append, until {@code wake} is satisfied (or, when {@code wake} is
     * {@link Wake#ANY}, any accepted event arrives) or {@code timeoutMillis} elapses.
     *
     * <p>A timeout returns an EMPTY batch with {@link Reason#TIMEOUT} and an unchanged cursor.
     * That is a successful call: "nothing happened" is information, and it is the idle floor of
     * the whole design — one call per {@link #MAX_WAIT_MILLIS} rather than one per curiosity.
     *
     * @param timeoutMillis clamped to {@link #MAX_WAIT_MILLIS}; a non-positive value means
     *                      "do not park at all", making this equivalent to {@link #since}.
     */
    public synchronized Batch await(long sinceSeq, Filter filter, int maxEvents,
                                    long timeoutMillis, Wake wake) throws InterruptedException {
        Wake w = wake == null ? Wake.ANY : wake;
        Batch immediate = collect(sinceSeq, filter, maxEvents, w);
        if (!immediate.events().isEmpty() || Reason.GAP.equals(immediate.reason())) {
            return immediate;
        }
        long budget = Math.min(Math.max(timeoutMillis, 0L), MAX_WAIT_MILLIS);
        if (budget == 0L) return timedOut(sinceSeq);

        // Deadline loop rather than a single wait(): wait() can return spuriously, and every
        // append notifies ALL readers, most of whom this event does not concern.
        long deadline = System.currentTimeMillis() + budget;
        while (true) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0L) return timedOut(sinceSeq);
            wait(remaining);
            Batch b = collect(sinceSeq, filter, maxEvents, w);
            if (!b.events().isEmpty() || Reason.GAP.equals(b.reason())) return b;
            // Accepted-but-not-wake-worthy events accumulate: keep parking, deliver at deadline.
        }
    }

    /**
     * Gather matching events after {@code sinceSeq}.
     *
     * <p>When {@code wake} is non-null and expresses a preference, a batch is only RETURNED (i.e.
     * non-empty) if it contains at least one wake-worthy event; otherwise the accepted events are
     * left for a later read. That is what makes accumulation work — the events are not lost, they
     * simply are not yet a reason to interrupt the caller.
     */
    private Batch collect(long sinceSeq, Filter filter, int maxEvents, Wake wake) {
        Filter f = filter == null ? Filter.ALL : filter;
        int cap = Math.min(maxEvents <= 0 ? MAX_BATCH : maxEvents, MAX_BATCH);

        long from = Math.max(sinceSeq, 0L);
        long oldest = dropped + 1;

        // The cursor fell off the back of the ring. Lead with GAP so the reader learns it has a
        // hole BEFORE it reads events that assume continuity.
        if (from + 1 < oldest && lastSeq > 0) {
            long missed = oldest - (from + 1);
            JsonObject body = new JsonObject();
            body.addProperty("missed", missed);
            body.addProperty("oldestAvailable", oldest);
            body.addProperty("guidance",
                    "Events were dropped before you read them. State you relied on may have "
                            + "changed. Re-read the companion's status resource before acting on "
                            + "any assumption formed earlier.");
            // seq = oldest-1 places it immediately before the first surviving event, so returning
            // this batch's cursor never replays or skips anything.
            JournalEvent gap = new JournalEvent(oldest - 1, JournalEvent.SCHEMA_VERSION, 0L,
                    JournalEvent.Type.GAP, JournalEvent.Provenance.SYSTEM, null, null, body);
            List<JournalEvent> out = new ArrayList<>();
            out.add(gap);
            long cursor = gap.seq();
            for (long s = oldest; s <= lastSeq && out.size() < cap; s++) {
                JournalEvent e = at(s);
                if (e == null) continue;
                cursor = s;
                if (f.accepts(e)) out.add(e);
            }
            return new Batch(Collections.unmodifiableList(out), cursor, Reason.GAP, cursor < lastSeq);
        }

        List<JournalEvent> out = new ArrayList<>();
        String wakeReason = null;
        long cursor = from;
        for (long s = Math.max(from + 1, oldest); s <= lastSeq; s++) {
            if (out.size() >= cap) {
                return new Batch(Collections.unmodifiableList(out), cursor,
                        wakeReason != null ? wakeReason : Reason.MAX_EVENTS, true);
            }
            JournalEvent e = at(s);
            cursor = s;
            if (e == null || !f.accepts(e)) continue;
            out.add(e);
            if (wake != null && wakeReason == null) {
                String r = wake.reasonFor(e);
                if (r != null) wakeReason = r;
            }
        }

        if (out.isEmpty()) return new Batch(List.of(), cursor, Reason.EVENTS, false);

        // Accumulation rule: a caller that asked to be woken only for specific things does not get
        // woken for anything else. The events stay in the ring and arrive on the next read.
        if (wake != null && !wake.isAny() && wakeReason == null) {
            return new Batch(List.of(), from, Reason.EVENTS, false);
        }
        return new Batch(Collections.unmodifiableList(out), cursor,
                wakeReason != null ? wakeReason : Reason.EVENTS, false);
    }

    private Batch timedOut(long sinceSeq) {
        return new Batch(List.of(), sinceSeq, Reason.TIMEOUT, false);
    }

    /** The event with this seq, or {@code null} if it has been overwritten or never existed. */
    private JournalEvent at(long seq) {
        if (seq < 1 || seq > lastSeq || seq <= dropped) return null;
        JournalEvent e = ring[(int) ((seq - 1) % capacity)];
        return (e != null && e.seq() == seq) ? e : null;
    }
}
