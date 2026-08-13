package com.dwinovo.numen.mcp.coordinator;

import java.util.UUID;

/**
 * One locked-trade negotiation between two participants. Lifecycle:
 * {@code INVITED → (LOCKED | DECLINED | EXPIRED)}, and from LOCKED to
 * {@code CLOSED}. All transitions are driven by cluster tool calls through
 * {@link Coordinator}; there is no background reaper — an invitation older
 * than the configured timeout is judged dead lazily, the next time anyone
 * touches the session ({@link #expired}).
 */
public final class TradeSession {

    public enum State { INVITED, LOCKED, CLOSED, DECLINED, EXPIRED }

    private final String id;
    private final UUID initiator;
    private final UUID target;
    private final String note;
    private final long createdAtMillis;
    private State state = State.INVITED;

    TradeSession(String id, UUID initiator, UUID target, String note) {
        this.id = id;
        this.initiator = initiator;
        this.target = target;
        this.note = note == null ? "" : note;
        this.createdAtMillis = System.currentTimeMillis();
    }

    public String id() { return id; }
    public UUID initiator() { return initiator; }
    public UUID target() { return target; }
    public String note() { return note; }
    public State state() { return state; }

    void setState(State s) { this.state = s; }

    public boolean isParty(UUID who) {
        return initiator.equals(who) || target.equals(who);
    }

    /** The other party, or null if {@code who} is not in this session. */
    public UUID other(UUID who) {
        if (initiator.equals(who)) return target;
        if (target.equals(who)) return initiator;
        return null;
    }

    /** Live = still negotiable (invited and not yet expired, or locked). */
    public boolean live(long inviteTimeoutSeconds) {
        if (state == State.LOCKED) return true;
        return state == State.INVITED && !expired(inviteTimeoutSeconds);
    }

    public boolean expired(long inviteTimeoutSeconds) {
        return state == State.INVITED
                && System.currentTimeMillis() - createdAtMillis > inviteTimeoutSeconds * 1000L;
    }
}
