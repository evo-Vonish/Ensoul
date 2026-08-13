package com.dwinovo.numen.mcp.coordinator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The cluster coordinator's state: who is playing (participants), what is
 * waiting for them (per-companion mailboxes), and which trades are on the
 * table (sessions).
 *
 * <h2>Who is a participant</h2>
 * A companion joins when an external brain takes it over through the MCP
 * server ({@code acquire_companion}) and leaves on {@code release_companion}.
 * Only participants can speak, be spoken to, and trade — an un-acquired
 * companion has no driver, so mail addressed to it could never be drained.
 *
 * <h2>Delivery semantics (owner-mandated: append, never interrupt)</h2>
 * Nothing here pushes. Messages accumulate in mailboxes and are rendered +
 * cleared by {@link #drainInbox}, which the MCP server calls at the tail of
 * any tool result addressed to that companion. The receiving model sees
 * messages strictly between its own tool calls.
 *
 * <h2>Threading</h2>
 * Called from the MCP HTTP pool; every public method is synchronized. No
 * background threads.
 */
public final class Coordinator {

    private final int mailboxCapacity;
    private final Map<UUID, String> participants = new LinkedHashMap<>();
    private final Map<UUID, Deque<Envelope>> mailboxes = new HashMap<>();
    private final Map<String, TradeSession> sessions = new LinkedHashMap<>();
    private final AtomicLong seq = new AtomicLong();
    private final AtomicLong tradeSeq = new AtomicLong();

    public Coordinator(int mailboxCapacity) {
        this.mailboxCapacity = Math.max(8, mailboxCapacity);
    }

    // ---- roster ----

    public synchronized void join(UUID who, String name) {
        participants.put(who, name);
        mailboxes.computeIfAbsent(who, k -> new ArrayDeque<>());
    }

    /** Unregister; force-closes any live session they were in, notifying the partner. */
    public synchronized void leave(UUID who, long inviteTimeoutSeconds) {
        String name = participants.remove(who);
        mailboxes.remove(who);
        for (TradeSession s : sessions.values()) {
            if (s.live(inviteTimeoutSeconds) && s.isParty(who)) {
                UUID partner = s.other(who);
                s.setState(TradeSession.State.CLOSED);
                if (partner != null) {
                    postTo(partner, Envelope.trade(seq.incrementAndGet(), name != null ? name : "someone",
                            "close", s.id() + " closed — the other agent left the cluster"));
                }
            }
        }
    }

    public synchronized boolean isParticipant(UUID who) {
        return participants.containsKey(who);
    }

    public synchronized String nameOf(UUID who) {
        return participants.get(who);
    }

    /** All participants, roster order, as {@code name (uuid)} — for list_companions annotations. */
    public synchronized List<String> rosterLines() {
        List<String> out = new ArrayList<>();
        participants.forEach((u, n) -> out.add(n + " (" + u + ")"));
        return out;
    }

    /** Resolve a name (exact, then case-insensitive) or uuid string to a participant uuid, or null. */
    public synchronized UUID resolveParticipant(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String r = raw.trim();
        for (Map.Entry<UUID, String> e : participants.entrySet()) {
            if (e.getKey().toString().equalsIgnoreCase(r)) return e.getKey();
        }
        for (Map.Entry<UUID, String> e : participants.entrySet()) {
            if (e.getValue().equals(r)) return e.getKey();
        }
        for (Map.Entry<UUID, String> e : participants.entrySet()) {
            if (e.getValue().equalsIgnoreCase(r)) return e.getKey();
        }
        return null;
    }

    // ---- messaging ----

    /** Broadcast to every participant except the sender. @return recipients reached. */
    public synchronized int broadcast(UUID from, String message) {
        String fromName = nameOr(from, "?");
        int n = 0;
        for (UUID who : participants.keySet()) {
            if (who.equals(from)) continue;
            postTo(who, Envelope.chat(seq.incrementAndGet(), fromName, Envelope.Kind.BROADCAST, message));
            n++;
        }
        return n;
    }

    /** Private message to one participant (target validity is the caller's job). */
    public synchronized void whisper(UUID from, UUID to, String message) {
        postTo(to, Envelope.chat(seq.incrementAndGet(), nameOr(from, "?"), Envelope.Kind.WHISPER, message));
    }

    /** Trade lifecycle notice into one mailbox (invite / accepted / declined / give / close). */
    public synchronized void notifyTrade(UUID to, String fromName, String event, String text) {
        postTo(to, Envelope.trade(seq.incrementAndGet(), fromName, event, text));
    }

    /**
     * Render and clear one mailbox. Empty → {@code ""}. Otherwise an
     * {@code <inbox>} block, one rendered {@link Envelope} per line, in
     * arrival order. This is the ONLY way messages leave a mailbox.
     */
    public synchronized String drainInbox(UUID who) {
        Deque<Envelope> box = mailboxes.get(who);
        if (box == null || box.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("<inbox>");
        Envelope e;
        while ((e = box.poll()) != null) {
            sb.append('\n').append(e.render());
        }
        sb.append("\n</inbox>");
        return sb.toString();
    }

    // ---- trade sessions ----

    /** @return error string, or null on success (session created + parked INVITED). */
    public synchronized String openInvite(UUID from, UUID to, String note, long inviteTimeoutSeconds) {
        reapExpired(inviteTimeoutSeconds);
        if (activeSessionOf(from, inviteTimeoutSeconds) != null) {
            return "you already have a live trade session — close it first (trade_close)";
        }
        if (activeSessionOf(to, inviteTimeoutSeconds) != null) {
            return nameOr(to, "target") + " is already in a trade session — try again later";
        }
        String id = "t" + tradeSeq.incrementAndGet();
        sessions.put(id, new TradeSession(id, from, to, note));
        return null;
    }

    /** The session with this id if it still matters to the caller, else null. */
    public synchronized TradeSession session(String id, long inviteTimeoutSeconds) {
        TradeSession s = sessions.get(id);
        if (s == null) return null;
        if (s.expired(inviteTimeoutSeconds)) s.setState(TradeSession.State.EXPIRED);
        return s;
    }

    /** The caller's live session (as either party), or null. */
    public synchronized TradeSession activeSessionOf(UUID who, long inviteTimeoutSeconds) {
        for (TradeSession s : sessions.values()) {
            if (s.isParty(who) && s.live(inviteTimeoutSeconds)) return s;
        }
        return null;
    }

    /** Mark dead invitations EXPIRED so they stop blocking new invites. */
    private void reapExpired(long inviteTimeoutSeconds) {
        for (TradeSession s : sessions.values()) {
            if (s.expired(inviteTimeoutSeconds)) s.setState(TradeSession.State.EXPIRED);
        }
    }

    // ---- internals ----

    private void postTo(UUID who, Envelope e) {
        Deque<Envelope> box = mailboxes.get(who);
        if (box == null) return;   // not a participant (anymore) — drop silently
        if (box.size() >= mailboxCapacity) box.pollFirst();
        box.addLast(e);
    }

    private String nameOr(UUID who, String fallback) {
        String n = participants.get(who);
        return n != null ? n : fallback;
    }
}
