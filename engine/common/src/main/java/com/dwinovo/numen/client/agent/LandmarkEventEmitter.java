package com.dwinovo.numen.client.agent;

import java.util.List;
import java.util.function.Consumer;

/**
 * Turns {@link LandmarkStore.LandmarkEvent}s into append-only {@code <landmark_event>}
 * context notes and pushes them down the conversation's <em>tail</em> (user-role)
 * channel — the {@code bufferedPrompts} sink the agent loop flushes at the next
 * protocol-valid boundary.
 *
 * <p>This is the write half of the L1 landmark seam. The engine keeps world
 * knowledge out of the (byte-frozen) system prefix; instead each <em>semantic</em>
 * change — a station discovered, confirmed destroyed, renamed, repurposed, or
 * position-corrected — is emitted once as an immutable tail event. Earlier events
 * for the same landmark id are never rewritten; the model reconstructs the current
 * picture by reading the event history in order.
 *
 * <p>Wording/formatting only lives here; the decision of <em>whether</em> something
 * changed is the store's. Constitution: everything this emits is a user-role tail
 * message, never a mid-conversation {@code system} message (which would move to the
 * front and break the prompt cache).
 */
public final class LandmarkEventEmitter {

    private final Consumer<String> tailSink;

    /** @param tailSink appends one user-role note to the conversation's buffered tail (e.g. {@code bufferedPrompts::add}). */
    public LandmarkEventEmitter(Consumer<String> tailSink) {
        this.tailSink = tailSink;
    }

    /** Emit each event as its own {@code <landmark_event>} tail note. No-op for an empty list. */
    public void emit(List<LandmarkStore.LandmarkEvent> events) {
        for (LandmarkStore.LandmarkEvent e : events) {
            tailSink.accept(toXml(e));
        }
    }

    /** Render one landmark event as the {@code <landmark_event>} XML shape (label omitted when absent). */
    public static String toXml(LandmarkStore.LandmarkEvent e) {
        StringBuilder sb = new StringBuilder();
        sb.append("<landmark_event type=\"").append(typeAttr(e.type()))
          .append("\" id=\"").append(e.id()).append("\">");
        if (e.label() != null && !e.label().isBlank()) {
            sb.append("\n  <label>").append(e.label()).append("</label>");
        }
        sb.append("\n  <dimension>").append(e.dim()).append("</dimension>");
        sb.append("\n  <position x=\"").append(e.x())
          .append("\" y=\"").append(e.y())
          .append("\" z=\"").append(e.z()).append("\"/>");
        sb.append("\n  <kind>").append(e.kind()).append("</kind>");
        sb.append("\n</landmark_event>");
        return sb.toString();
    }

    private static String typeAttr(LandmarkStore.ChangeType t) {
        return switch (t) {
            case ADDED -> "added";
            case REMOVED -> "removed";
            case RENAMED -> "renamed";
            case REPURPOSED -> "repurposed";
            case POSITION_CORRECTED -> "position_corrected";
        };
    }
}
