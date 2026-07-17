package com.dwinovo.numen.client.agent;

/**
 * §3 event-envelope stamping: string surgery that injects the shared envelope
 * attributes ({@code seq}, {@code schemaVersion}, and {@code gameTime} when
 * available) into the ROOT tag of an event XML string. Applied centrally at the
 * client convergence point ({@link EntityAgentLoop}'s tail-note queue) so every
 * producer — injected world events, landmark events, context snapshots, date
 * notices, committed inferences — gets the envelope without knowing about it.
 *
 * <p>Deliberately NOT a full XML parser: our producers emit well-formed single
 * root elements, so targeted surgery (insert right after the tag name, before
 * any existing attributes) is robust for everything we generate. Rules:
 * <ul>
 *   <li>only strings that <em>begin</em> with an XML element open tag are
 *       stampable ({@code '<'} + letter) — plain owner text passes through
 *       untouched;</li>
 *   <li>a root tag already carrying a {@code seq="} attribute is left alone
 *       (idempotence — never double-stamp);</li>
 *   <li>{@code provenance} is never invented here: it belongs to producers,
 *       and per protocol its absence means "observed".</li>
 * </ul>
 */
final class EventEnvelope {

    /** Wire schema version of the event envelope — bump only on format breaks (§3). */
    static final String SCHEMA_VERSION = "1";

    private EventEnvelope() {}

    /**
     * True when {@code note} starts with an XML element open tag whose root tag
     * does not already carry a {@code seq} attribute.
     */
    static boolean isStampable(String note) {
        if (note == null || note.length() < 3 || note.charAt(0) != '<') return false;
        if (!Character.isLetter(note.charAt(1))) return false;   // excludes </, <!, <?
        int tagEnd = note.indexOf('>');
        if (tagEnd < 0) return false;
        return !note.substring(0, tagEnd).contains(" seq=\"");
    }

    /**
     * Insert {@code seq="…" schemaVersion="1"} (plus {@code gameTime="…"} when
     * {@code gameTime} is non-null) immediately after the root tag NAME, ahead of
     * any existing attributes. Caller must have checked {@link #isStampable} first.
     */
    static String stamp(String note, long seq, Long gameTime) {
        int i = 1;
        while (i < note.length() && isNameChar(note.charAt(i))) i++;
        StringBuilder attrs = new StringBuilder(48);
        attrs.append(" seq=\"").append(seq).append("\" schemaVersion=\"").append(SCHEMA_VERSION).append('"');
        if (gameTime != null) {
            attrs.append(" gameTime=\"").append(gameTime.longValue()).append('"');
        }
        return note.substring(0, i) + attrs + note.substring(i);
    }

    /** XML name characters we emit in root tags (letters, digits, {@code - _ :}). */
    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == ':';
    }
}
