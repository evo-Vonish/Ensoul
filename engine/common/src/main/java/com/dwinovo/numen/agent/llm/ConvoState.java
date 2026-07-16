package com.dwinovo.numen.agent.llm;

import com.dwinovo.numen.agent.provider.AssistantTurn;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Per-entity conversation history. Lives on the **client** side now (LLM moved
 * off-server in the per-player-pays-tokens refactor), accessed solely from the
 * client main thread — no synchronisation needed.
 *
 * <h2>Storage type</h2>
 * Plain provider-agnostic DTOs ({@link AssistantTurn} et al.) — no SDK
 * classes leak through. The provider layer translates these to / from wire
 * JSON when building requests and parsing responses.
 *
 * <h2>No loop guard, no turn cap</h2>
 * There is intentionally no autonomous stop: a capable agent legitimately
 * chains many tasks, and retrying a timed-out task repeats the exact same
 * tool call — a signature-based loop detector kept killing that correct
 * recovery (it aborted a {@code move_to} resume after a mid-journey timeout).
 * Runaways are stopped by the owner's interrupt; {@link #turnCount} is kept
 * purely for log numbering.
 */
public final class ConvoState {

    /** Tagged union for conversation history. */
    public sealed interface Msg permits Msg.User, Msg.Assistant, Msg.Tool {
        /**
         * An owner message. {@code attachments} are absolute paths to persisted
         * image files under {@code config/numen/attachments/<uuid>/} that ride
         * along with this turn (empty for the overwhelmingly common text-only
         * case). Kept as file paths, not bytes: the image is base64-inlined only
         * at request-build time, and only for the most recent user message (see
         * {@code NumenLlmClient}), so history stays cheap.
         */
        record User(String content, List<String> attachments) implements Msg {
            /** Text-only convenience — the common case; no image attachments. */
            public User(String content) { this(content, List.of()); }
            /** Normalise null / defensively copy so the record stays immutable. */
            public User {
                attachments = attachments == null ? List.of() : List.copyOf(attachments);
            }
        }
        record Assistant(AssistantTurn turn) implements Msg {}
        record Tool(String toolCallId, String content) implements Msg {}
    }

    private final List<Msg> messages = new ArrayList<>();
    private int turnCount = 0;

    /**
     * Monotonic <strong>structural</strong> epoch — the identity token an ICE async
     * (long-track) compaction snapshots to detect that the retired region moved under
     * it. Bumped ONLY by the whole-history rewrites ({@link #replaceAll} / {@link #clear})
     * and the partial recast ({@link #replacePrefix}); plain {@link #push appends} leave
     * it untouched. Because {@link Msg} records are immutable and existing indices are
     * never mutated in place, "same epoch since snapshot" is a sound proof that
     * {@code messages[0..cutIndex)} is still byte-identical to what was summarized.
     */
    private long structuralEpoch = 0;

    /** Notified after every append — the persistence hook ({@link ConvoLog#append}). */
    private final Consumer<Msg> sink;

    /** In-memory only (tests, or persistence explicitly disabled). */
    public ConvoState() {
        this(m -> { });
    }

    /** Every appended message is also handed to {@code sink} (e.g. the JSONL log). */
    public ConvoState(Consumer<Msg> sink) {
        this.sink = sink;
    }

    /**
     * Splice previously persisted history in at construction time, WITHOUT
     * notifying the sink — these messages are already on disk; re-appending
     * them would duplicate the file on every game launch.
     */
    public void preload(List<Msg> history) {
        messages.addAll(history);
    }

    /**
     * Swap the entire history for {@code replacement} WITHOUT notifying the
     * sink — used by compaction, which records its boundary in the log through
     * its own channel ({@link ConvoLog#appendCompactSummary}) rather than as
     * ordinary appended messages.
     */
    public void replaceAll(List<Msg> replacement) {
        messages.clear();
        messages.addAll(replacement);
        structuralEpoch++;
        resetTurnCount();
    }

    /**
     * ICE §7 sanctioned recast, <em>partial</em> form (the field implementation of the
     * doc's "long-track" retirement): retire the messages {@code [0, cutIndex)} — the
     * "已退休区 / retired region" — replacing them with the single {@code summary} user
     * message, while keeping {@code messages[cutIndex..]} — the live tail plus everything
     * appended while the async summarization was in flight — <strong>byte-identical</strong>
     * (the exact same immutable {@link Msg} records, in order).
     *
     * <p>Mirrors {@link #replaceAll}'s persistence semantics: the sink is NOT notified here.
     * The caller records the boundary through {@link ConvoLog#appendCompactPrefixBoundary}
     * (a {@code compact} divider followed by a fresh copy of the surviving tail) so a
     * relaunch replays {@code [summary] + tail}. Bumps {@link #structuralEpoch} so any
     * async snapshot taken before this recast is recognised as stale.
     */
    public void replacePrefix(int cutIndex, Msg summary) {
        if (cutIndex < 0 || cutIndex > messages.size()) {
            throw new IndexOutOfBoundsException(
                    "cutIndex " + cutIndex + " out of [0," + messages.size() + "]");
        }
        List<Msg> tail = new ArrayList<>(messages.subList(cutIndex, messages.size()));
        messages.clear();
        messages.add(summary);
        messages.addAll(tail);
        structuralEpoch++;
        resetTurnCount();
    }

    /**
     * The current structural epoch — snapshot it alongside a cut index before dispatching
     * an async compaction; if it has changed by splice time, a whole-history rewrite
     * (blocking compaction / reset) intervened and the async result must be discarded.
     */
    public long structuralEpoch() {
        return structuralEpoch;
    }

    public void addUser(String content) {
        push(new Msg.User(content));
    }

    /** Owner message carrying image attachment file paths (see {@link Msg.User}). */
    public void addUser(String content, List<String> attachments) {
        push(new Msg.User(content, attachments));
    }

    public void addAssistant(AssistantTurn turn) {
        push(new Msg.Assistant(turn));
    }

    public void addToolResult(String toolCallId, String content) {
        push(new Msg.Tool(toolCallId, content));
    }

    private void push(Msg msg) {
        messages.add(msg);
        sink.accept(msg);
    }

    public List<Msg> snapshot() {
        return List.copyOf(messages);
    }

    /** Most recent message, or {@code null} when the history is empty. Used by
     *  the agent loop's interrupt path to keep the conversation protocol-valid
     *  (avoid leaving a trailing {@code user} message after an aborted turn). */
    public Msg lastMessage() {
        return messages.isEmpty() ? null : messages.get(messages.size() - 1);
    }

    public int turnCount() { return turnCount; }
    public void incrementTurn() { turnCount++; }

    /**
     * Called when a final text response arrives (no tool_calls). The chain
     * is settled; the next user message starts a fresh log-numbering count.
     */
    public void resetTurnCount() {
        turnCount = 0;
    }

    /** Wipe in-memory history. Called on explicit reset (Phase-2: /numen reset
     *  command). NOTE: does not touch the sink's storage — a caller owning a
     *  {@link ConvoLog} must also {@code delete()} it, or the next launch
     *  resurrects everything just cleared. */
    public void clear() {
        messages.clear();
        structuralEpoch++;
        resetTurnCount();
    }
}
