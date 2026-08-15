package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.entity.ControlRegistry.ControlState;
import com.dwinovo.numen.network.payload.ControlStatePayload;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Client-side mirror of {@link com.dwinovo.numen.entity.ControlRegistry} — the answer to "who is
 * driving this companion right now", as last told to us by the server. Holds ONLY the latest
 * pushed {@link ControlStatePayload} snapshot, keyed by companion UUID. Fed exclusively by {@link
 * ControlStatePayload#handle}; there is no other writer anywhere in client code, because there is
 * no packet by which a client can ASSERT control state — {@code ControlRequestPayload} is a
 * request, {@code ControlStatePayload} is the only answer. See the design doc's "CLIENT
 * DISAGREEMENT" failure-handling note.
 *
 * <h2>Level-triggered reconciliation, never a merge</h2>
 * {@link #replaceAll} throws away the previous view entirely and rebuilds it from the fresh
 * payload — the same full-replacement contract as {@link NumenRoster#replaceAll} /
 * {@code CompanionListPayload}. A companion the server stops mentioning (dismissed, or simply not
 * this owner's) is dropped rather than left to linger. Because the server resends this snapshot
 * on every transition, on login, AND as an unconditional keep-alive every 20 ticks, a single
 * dropped packet self-corrects on the very next push — this class never needs to guess whether a
 * silence means "still true" or "missed an update".
 *
 * <h2>Threading</h2>
 * Client main thread only (the platform layer guarantees {@code ControlStatePayload}'s handler
 * runs there) — same convention as {@link NumenRoster} / {@code ClientDeaths}.
 */
public final class ClientControl {

    /** One companion's last-known control line, plus local bookkeeping the HUD/console read. */
    public record Snapshot(ControlState state, String controllerLabel, String sessionToken,
                            int ttlRemainingTicks, int idleTicks, long receivedAtMillis,
                            long lastStateChangeMillis) {}

    /** Fired when a snapshot this client held a non-empty {@code sessionToken} for arrives WITHOUT
     *  one — the lease is gone (expired, force-released, or raced by another RELEASE) and whoever
     *  was waiting on it needs to be told, not left hanging on a bare timeout. */
    @FunctionalInterface
    public interface LeaseLostListener {
        void onLeaseLost(UUID companion, String reason);
    }

    private static ClientControl instance;

    private final Map<UUID, Snapshot> snapshots = new HashMap<>();
    private final List<Runnable> listeners = new ArrayList<>();
    private LeaseLostListener leaseLostListener = (companion, reason) -> {};

    private ClientControl() {}

    public static ClientControl instance() {
        if (instance == null) {
            instance = new ClientControl();
        }
        return instance;
    }

    /** Notified after every {@link #replaceAll} and {@link #clear} — e.g. the HUD (W15) and the
     *  external-control console (W16) repainting from the fresh snapshot. Multiple listeners may
     *  register; none are ever removed (both consumers live for the client session). */
    public void registerListener(Runnable listener) {
        if (listener != null) listeners.add(listener);
    }

    /** The one consumer (W9's server-backed {@code NumenActuator}) that resolves pending external
     *  futures when a lease disappears out from under them. Registering a new listener replaces
     *  the previous one, matching {@code ControlRegistry.setHandoverHook}'s single-hook contract. */
    public void setLeaseLostListener(LeaseLostListener listener) {
        leaseLostListener = listener == null ? (companion, reason) -> {} : listener;
    }

    /** Full replacement from a fresh server push — see the class doc comment. Never a merge. */
    public void replaceAll(ControlStatePayload payload) {
        long now = System.currentTimeMillis();
        Map<UUID, Snapshot> next = new HashMap<>();
        for (ControlStatePayload.Entry e : payload.states()) {
            ControlState state = decodeState(e.state());
            Snapshot prev = snapshots.get(e.companion());
            // Lease-lost detection: we previously held a real token for this companion and the
            // fresh snapshot no longer carries one. Fire before we overwrite prev so the listener
            // still sees whatever it needs from the outgoing snapshot if it wants it later.
            if (prev != null && !prev.sessionToken().isEmpty() && e.sessionToken().isEmpty()) {
                leaseLostListener.onLeaseLost(e.companion(),
                        "server control-state sync no longer reports this lease");
            }
            boolean changed = prev == null || prev.state() != state;
            long lastChange = changed ? now : prev.lastStateChangeMillis();
            next.put(e.companion(), new Snapshot(state, e.controllerLabel(), e.sessionToken(),
                    e.ttlRemainingTicks(), e.idleTicks(), now, lastChange));
        }
        snapshots.clear();
        snapshots.putAll(next);
        notifyListeners();
    }

    /** {@code BUILTIN} for a companion this client has never received a snapshot line for — the
     *  correct default: every companion boots at baseline BUILTIN server-side (see {@code
     *  ControlRegistry.effective}), so "unknown" and "BUILTIN" are the same fact until the first
     *  push (which, per the keep-alive cadence, is at most 20 ticks away). */
    public ControlState stateOf(UUID companion) {
        Snapshot s = snapshots.get(companion);
        return s == null ? ControlState.BUILTIN : s.state();
    }

    /** Whether the built-in brain may start a turn for this companion right now. */
    public boolean mayThink(UUID companion) {
        return stateOf(companion) == ControlState.BUILTIN;
    }

    /** The lease token this client's controller currently holds for {@code companion}, or {@code
     *  ""} when none — what {@code CoreServerTools.ship} attaches to outgoing tool calls (W4). */
    public String tokenFor(UUID companion) {
        Snapshot s = snapshots.get(companion);
        return s == null ? "" : s.sessionToken();
    }

    /** The full last-known line for {@code companion}, or {@code null} if never reported. */
    public Snapshot snapshotOf(UUID companion) {
        return snapshots.get(companion);
    }

    /** Wipe every snapshot (disconnect teardown, W7) — with nothing pushing a replacement, every
     *  companion reads back as {@code BUILTIN} via {@link #stateOf} until the next server session. */
    public void clear() {
        snapshots.clear();
        notifyListeners();
    }

    private void notifyListeners() {
        for (Runnable r : listeners) r.run();
    }

    /** Unrecognised wire values (a future server ahead of this client) fall back to BUILTIN rather
     *  than throwing — matches the payload's role as an advisory sync, not a contract to enforce. */
    private static ControlState decodeState(byte wire) {
        ControlState[] values = ControlState.values();
        return wire >= 0 && wire < values.length ? values[wire] : ControlState.BUILTIN;
    }
}
