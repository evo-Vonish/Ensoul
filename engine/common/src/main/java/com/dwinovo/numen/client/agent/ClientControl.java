package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.entity.ControlRegistry.ControlState;
import com.dwinovo.numen.network.payload.ControlStatePayload;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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

    /**
     * W9 — outcome of a pending {@code ACQUIRE}/{@code RELEASE} round trip, resolved from the
     * server's next answering {@link ControlStatePayload} (see {@link #awaitAcquire} /
     * {@link #awaitRelease}) or a timeout. {@code sessionToken} is only meaningful on a granted
     * acquire; empty otherwise. Package-neutral on purpose — {@code com.dwinovo.numen.api}
     * depends on {@code client.agent}, never the other way, so this type lives here rather than
     * on {@code NumenActuator}, and {@code NumenActuator} maps it to its own public result type.
     */
    public record RequestOutcome(boolean ok, String sessionToken, String reason) {}

    /** Answers "does this fresh entry settle the pending request for its companion, and how?" —
     *  {@code null} means "not yet, keep waiting for the next push or the timeout". {@code entry}
     *  is {@code null} when the companion is missing from the fresh snapshot entirely (dismissed,
     *  or simply not this owner's — {@link #replaceAll} only calls a resolver for entries actually
     *  present, so in practice this is always non-null today; the null case is future-proofing). */
    @FunctionalInterface
    private interface PendingResolver {
        RequestOutcome resolve(ControlStatePayload.Entry entry);
    }

    private record Pending(CompletableFuture<RequestOutcome> future, PendingResolver resolver) {}

    private static ClientControl instance;

    private final Map<UUID, Snapshot> snapshots = new HashMap<>();
    private final List<Runnable> listeners = new ArrayList<>();
    private LeaseLostListener leaseLostListener = (companion, reason) -> {};
    /** At most one outstanding ACQUIRE/RELEASE request per companion — a caller is expected to
     *  serialize its own requests for one companion (the normal acquire→invoke*→release contract
     *  never issues two at once). A fresh request replaces whatever was pending; the replaced
     *  future is left to its own timeout rather than force-completed, since by the time a second
     *  request lands the first's answer may still arrive and could be the truthful one. */
    private final Map<UUID, Pending> pending = new HashMap<>();

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
            // W9: does this fresh line answer a pending acquire/release for its companion?
            resolvePending(e.companion(), e);
        }
        snapshots.clear();
        snapshots.putAll(next);
        notifyListeners();
    }

    // ==================================================== W9: pending ACQUIRE/RELEASE round trips

    /** Default answer-wait — {@code NumenActuator}'s acquire/release "server round trip" is not a
     *  literal blocking RPC (there is no per-request correlation id on the wire, only the
     *  ever-resending {@link ControlStatePayload}), so this bounds how long a caller waits for an
     *  answer that, for whatever reason, never distinguishes itself from an unrelated keep-alive
     *  push (see {@link #awaitAcquire} / {@link #awaitRelease}). */
    private static final long DEFAULT_REQUEST_TIMEOUT_MILLIS = 10_000L;

    /**
     * Register a pending {@code ACQUIRE} for {@code companion}, requested under {@code label}.
     * Resolves the moment a {@link ControlStatePayload} entry for this companion arrives showing
     * either: (a) {@code EXTERNAL} under exactly this {@code label} with a non-empty token — WE
     * won the race, success; or (b) {@code EXTERNAL} under a DIFFERENT label — someone else won
     * it first, fast-fail with their label as the reason. Any other state (not yet EXTERNAL) is
     * not an answer yet and keeps waiting. If no push ever resolves it, times out after {@code
     * timeoutMillis} with a failure — the server never answering at all (e.g. the companion isn't
     * this owner's, so {@code ControlRequestPayload.handle} returns silently) must not hang the
     * caller forever.
     */
    public CompletableFuture<RequestOutcome> awaitAcquire(UUID companion, String label, long timeoutMillis) {
        String wantLabel = label == null ? "" : label;
        return awaitAnswer(companion, timeoutMillis, entry -> {
            if (entry == null || decodeState(entry.state()) != ControlState.EXTERNAL) {
                return null;   // not decided yet
            }
            if (wantLabel.equals(entry.controllerLabel()) && !entry.sessionToken().isEmpty()) {
                return new RequestOutcome(true, entry.sessionToken(), "");
            }
            return new RequestOutcome(false, "", "another brain holds it (" + entry.controllerLabel() + ")");
        });
    }

    /**
     * Register a pending {@code RELEASE} for {@code companion}, releasing {@code sessionToken}.
     * Resolves success the moment a fresh entry shows a DIFFERENT (or empty) token for this
     * companion than the one being released — our release took effect, or something else already
     * cleared it (force-release, expiry racing our own request); either way we no longer hold it,
     * which is the only fact the caller needs. Still shows OUR token → not released yet, keeps
     * waiting (up to the timeout, then a failure — see {@link #awaitAcquire}'s reasoning).
     */
    public CompletableFuture<RequestOutcome> awaitRelease(UUID companion, String sessionToken, long timeoutMillis) {
        String releasing = sessionToken == null ? "" : sessionToken;
        return awaitAnswer(companion, timeoutMillis, entry -> {
            if (entry == null || !releasing.equals(entry.sessionToken())) {
                return new RequestOutcome(true, "", "");
            }
            return null;   // still shows our token — not released yet
        });
    }

    private CompletableFuture<RequestOutcome> awaitAnswer(UUID companion, long timeoutMillis, PendingResolver resolver) {
        CompletableFuture<RequestOutcome> future = new CompletableFuture<>();
        pending.put(companion, new Pending(future, resolver));
        long wait = timeoutMillis > 0 ? timeoutMillis : DEFAULT_REQUEST_TIMEOUT_MILLIS;
        // Bounce the timeout back onto the client main thread (delayedExecutor itself fires on a
        // JDK scheduler thread) — pending/snapshots are main-thread-only state, same convention
        // as every other client.agent registry.
        CompletableFuture.delayedExecutor(wait, TimeUnit.MILLISECONDS)
                .execute(() -> Minecraft.getInstance().execute(() -> timeoutPending(companion, future)));
        return future;
    }

    /** Called on every fresh entry from {@link #replaceAll} — completes and clears the pending
     *  request for {@code companion}, if any, when {@code entry} answers it. */
    private void resolvePending(UUID companion, ControlStatePayload.Entry entry) {
        Pending p = pending.get(companion);
        if (p == null || p.future().isDone()) return;
        RequestOutcome outcome = p.resolver().resolve(entry);
        if (outcome != null) {
            pending.remove(companion);
            p.future().complete(outcome);
        }
    }

    /** Fires {@link #DEFAULT_REQUEST_TIMEOUT_MILLIS} (or the caller's override) after a pending
     *  request was registered. A no-op if it already resolved via a payload, or if a later
     *  request for the same companion has since replaced this one in {@link #pending} (that
     *  request's own timeout governs it now). */
    private void timeoutPending(UUID companion, CompletableFuture<RequestOutcome> future) {
        if (future.isDone()) return;
        Pending p = pending.get(companion);
        if (p != null && p.future() == future) {
            pending.remove(companion);
        }
        future.complete(new RequestOutcome(false, "", "timed out waiting for the server to answer"));
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
     *  companion reads back as {@code BUILTIN} via {@link #stateOf} until the next server session.
     *  Also fails any pending {@link #awaitAcquire}/{@link #awaitRelease} request outright — the
     *  connection they were waiting an answer over is gone, so there is no point making the
     *  caller wait out the full timeout to learn that. */
    public void clear() {
        snapshots.clear();
        if (!pending.isEmpty()) {
            for (Pending p : new ArrayList<>(pending.values())) {
                p.future().complete(new RequestOutcome(false, "", "disconnected before the server answered"));
            }
            pending.clear();
        }
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
