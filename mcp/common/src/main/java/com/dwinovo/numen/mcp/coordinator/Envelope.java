package com.dwinovo.numen.mcp.coordinator;

/**
 * One cluster message parked in a participant's mailbox. Drained (and thereby
 * marked delivered) the next time that participant's driver makes any tool
 * call — see {@link Coordinator#drainInbox}.
 *
 * <p>{@link #render()} emits the one-line XML-ish form appended inside the
 * {@code <inbox>} block at the tail of a tool result. Kept deliberately flat
 * and attribute-light: models parse this reliably, and it diffs cleanly in
 * match logs ({@code seq} is cluster-monotonic).
 */
public record Envelope(long seq, long epochMillis, String from, Kind kind, String event, String text) {

    public enum Kind { BROADCAST, WHISPER, TRADE }

    /** Chat message: {@code <msg seq from kind>text</msg>}. */
    public static Envelope chat(long seq, String from, Kind kind, String text) {
        return new Envelope(seq, System.currentTimeMillis(), from, kind, null, text);
    }

    /** Trade lifecycle event: {@code <trade seq from event>text</trade>}. */
    public static Envelope trade(long seq, String from, String event, String text) {
        return new Envelope(seq, System.currentTimeMillis(), from, Kind.TRADE, event, text);
    }

    public String render() {
        if (kind == Kind.TRADE) {
            return "<trade seq=\"" + seq + "\" from=\"" + esc(from) + "\" event=\"" + esc(event) + "\">"
                    + esc(text) + "</trade>";
        }
        return "<msg seq=\"" + seq + "\" from=\"" + esc(from) + "\" kind=\""
                + kind.name().toLowerCase() + "\">" + esc(text) + "</msg>";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("\n", " ").replace("\r", " ");
    }
}
