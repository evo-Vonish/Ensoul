package com.dwinovo.numen.entity;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.network.payload.ControlStatePayload;
import com.dwinovo.numen.platform.Services;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * THE server-authoritative answer to "who may drive this companion's body right now" — see the
 * design doc's control-authority state machine. Server-main-thread-only, process-scoped, and
 * deliberately NOT persisted: after a server restart no external controller (MCP session) can
 * possibly exist, so a persisted {@link ControlState#EXTERNAL} lease would freeze the body forever
 * with nobody left able to release it. Every companion therefore boots at baseline {@code BUILTIN}
 * with no lease, exactly like {@code CompanionTickDispatcher.ACTIVE}/{@code QUEUES},
 * {@code Companions.respawnHoldUntil} and {@code AutonomyScheduler.STATES} — the codebase's own
 * idiom for "live, server-thread, UUID-keyed, cleaned up through lifecycle hooks" state.
 *
 * <h2>Why not on {@link CompanionRegistry.Entry} or {@link NumenPlayer}</h2>
 * {@link CompanionRegistry} is world-saved: every {@code put}/{@code markDead}/{@code markAlive}
 * calls {@code setDirty()}, so heartbeat-frequency lease writes would thrash {@code companions.dat},
 * and a persisted lease surviving a reload is exactly the frozen-body failure above. {@link
 * NumenPlayer} is disqualified twice over — it is a brand-new object after every death/respawn and
 * does not exist at all while dormant, yet control must be answerable while dormant (a tool call can
 * arrive for a dormant companion before its body is respawned to serve it). No field is added to
 * either; this class is the single, separate authority.
 *
 * <h2>Derived, never duplicated</h2>
 * {@code effective = (live lease exists) ? EXTERNAL : baseline}. There is no second "is external"
 * flag anywhere in this class or its payload — every reader computes it from the lease + baseline.
 *
 * <h2>Cross-module seam</h2>
 * This class must not import {@code mod/} code (engine must not depend on core). Two callbacks let
 * the mod side plug in without an inverted dependency: {@link #setTaskRunningPredicate} (so the
 * lease-expiry sweep never strands a body mid-task) and {@link #setHandoverHook} (so expiry / force
 * release / dismissal can cancel the mod's task queue and resolve in-flight futures). Both default to
 * a no-op until the mod side registers them.
 */
@com.dwinovo.numen.api.Internal
public final class ControlRegistry {

    /** Wire encoding for {@link ControlStatePayload.Entry#state()} is this enum's ORDINAL — NONE=0,
     *  BUILTIN=1, EXTERNAL=2 — by design, so the payload never needs its own mapping table. Do not
     *  reorder these constants without updating the payload's doc comment to match. */
    public enum ControlState { NONE, BUILTIN, EXTERNAL }

    /** A live external lease. Immutable; renewal produces a new instance via {@link #withHeartbeat}. */
    public record Lease(String controllerId, String sessionToken, String label, UUID ownerUuid,
                         long acquiredGameTime, long lastHeartbeatGameTime, long ttlTicks) {
        Lease withHeartbeat(long now) {
            return new Lease(controllerId, sessionToken, label, ownerUuid, acquiredGameTime, now, ttlTicks);
        }
    }

    /** One companion's control record: its owner-chosen baseline (BUILTIN or NONE only) plus the
     *  live lease, if any. {@code lease == null} means the effective state IS the baseline. */
    private record Entry(ControlState baseline, Lease lease) {}

    /** The answer to an authorization check: whether the call may proceed, and — when it may not —
     *  a human-readable reason to surface to whichever brain was refused. */
    public record Decision(boolean allowed, String reason) {}

    /** Cancels the mod's task queue for a companion and resolves any in-flight futures when control
     *  changes hands — see the design doc's "handover hook" (shared by release / expiry / force
     *  release / dismissal). Registered once by the mod side; engine code never implements this. */
    @FunctionalInterface
    public interface HandoverHook {
        void handover(MinecraftServer server, UUID companion, String reason);
    }

    /** Default TTL when the request doesn't specify one: 6000 ticks = 5 minutes. */
    private static final long DEFAULT_TTL_TICKS = 6000L;
    /** Clamp bounds for a requested TTL: 1200 ticks (60 s) .. 24000 ticks (20 min). */
    private static final long MIN_TTL_TICKS = 1200L;
    private static final long MAX_TTL_TICKS = 24000L;
    /** Keep-alive resend cadence — matches {@code NumenPlayer.tick}'s existing "% 10" idiom for a
     *  periodic, cheap, self-healing push (here every 20 ticks = 1 s). */
    private static final long KEEP_ALIVE_INTERVAL_TICKS = 20L;

    private static final SecureRandom TOKEN_SOURCE = new SecureRandom();

    private static final Map<UUID, Entry> ENTRIES = new HashMap<>();

    /** Cache of the most recent game-time {@link #tick} observed. Exists so the no-server call sites
     *  ({@link #renew}, {@link #touch}, {@link #setBaseline}, {@link #forget} — deliberately not given
     *  a {@link MinecraftServer} reference, since {@link #touch} in particular is called once PER
     *  RUNNING TASK PER TICK and threading a server reference through that hot path for no reason
     *  invites someone to "helpfully" add a push there) can still stamp a reasonably fresh heartbeat
     *  time. Off by at most one server tick (~50 ms) against real game time — irrelevant next to TTLs
     *  measured in thousands of ticks. Zero until the first {@link #tick} call, which is harmless: no
     *  lease can exist yet for anything to renew against.
     */
    private static long cachedGameTime;

    private static Predicate<UUID> taskRunningPredicate = u -> false;
    private static HandoverHook handoverHook = (server, companion, reason) -> {};

    private ControlRegistry() {}

    // ==================================================== mod-side wiring (defaults to no-op)

    /** The mod registers a predicate answering "is a task attributed to this companion's lease
     *  currently RUNNING?" so the expiry sweep in {@link #tick} can refuse to strand a moving body. */
    public static void setTaskRunningPredicate(Predicate<UUID> predicate) {
        taskRunningPredicate = predicate == null ? u -> false : predicate;
    }

    /** The mod registers the handover hook (task-queue cancellation + future resolution) run on
     *  release / expiry / force release / dismissal — see the design doc's "handover hook" section. */
    public static void setHandoverHook(HandoverHook hook) {
        handoverHook = hook == null ? (server, companion, reason) -> {} : hook;
    }

    // ==================================================== reads

    /** {@code EXTERNAL} while a live lease exists, else the companion's baseline. An absent entry
     *  (never seen this server run) is {@code BUILTIN} — the default baseline for every companion. */
    public static ControlState effective(UUID companion) {
        Entry e = ENTRIES.get(companion);
        if (e == null) return ControlState.BUILTIN;
        return e.lease() != null ? ControlState.EXTERNAL : e.baseline();
    }

    /** The live lease, or {@code null} when the effective state is not {@code EXTERNAL}. */
    public static Lease leaseOf(UUID companion) {
        Entry e = ENTRIES.get(companion);
        return e == null ? null : e.lease();
    }

    /** The owner-chosen baseline (ignoring any live lease) — {@code BUILTIN} for an unseen companion. */
    public static ControlState baselineOf(UUID companion) {
        Entry e = ENTRIES.get(companion);
        return e == null ? ControlState.BUILTIN : e.baseline();
    }

    // ==================================================== writes

    /** Mint a fresh, unguessable session token for a new lease. 18 random bytes, URL-safe Base64
     *  without padding (24 chars) — well inside the payload's {@code stringUtf8(64)} bound, and 144
     *  bits is not a viable guessing target. Called by the C→S request handler BEFORE {@link
     *  #acquire}, not by {@code acquire} itself, so acquire stays a pure compare-and-set over its
     *  inputs. */
    public static String mintToken() {
        byte[] bytes = new byte[18];
        TOKEN_SOURCE.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Compare-and-set acquire: succeeds only when no live lease exists for {@code companion} — a
     *  second ACQUIRE while one is live is refused, NEVER steals the body out from under a holder. */
    public static boolean acquire(MinecraftServer server, UUID companion, UUID owner, String controllerId,
                                   String label, int ttlSeconds, String mintedToken) {
        Entry e = ENTRIES.get(companion);
        if (e != null && e.lease() != null) return false;   // already held — refuse, don't steal
        ControlState baseline = e == null ? ControlState.BUILTIN : e.baseline();
        long now = gameTime(server);
        Lease lease = new Lease(controllerId, mintedToken, label, owner, now, now, clampTtl(ttlSeconds));
        ENTRIES.put(companion, new Entry(baseline, lease));
        logTransition(companion, baseline, ControlState.EXTERNAL, label, "acquired by " + controllerId);
        syncToOwnerIfOnline(server, owner);
        return true;
    }

    /** Heartbeat renewal: only extends the lease when {@code sessionToken} matches — a mismatching
     *  token changes nothing (refused silently; the caller doesn't hold this companion). No
     *  {@link MinecraftServer} parameter on purpose — see {@link #cachedGameTime}. */
    public static boolean renew(UUID companion, String sessionToken) {
        Entry e = ENTRIES.get(companion);
        if (e == null || e.lease() == null) return false;
        if (sessionToken == null || sessionToken.isEmpty() || !sessionToken.equals(e.lease().sessionToken())) {
            return false;
        }
        ENTRIES.put(companion, new Entry(e.baseline(), e.lease().withHeartbeat(cachedGameTime)));
        return true;
    }

    /** Unconditional renewal for a tick on which a task attributed to this companion's lease is
     *  RUNNING — so a lease can never be shorter than the work it authorised. No token check: the
     *  caller (the tick dispatcher) already knows which lease the running task is attributed to. */
    public static void touch(UUID companion) {
        Entry e = ENTRIES.get(companion);
        if (e == null || e.lease() == null) return;
        ENTRIES.put(companion, new Entry(e.baseline(), e.lease().withHeartbeat(cachedGameTime)));
    }

    /** Owner-initiated release: the presented token must match the live lease. Runs the handover
     *  hook, then falls back to the companion's baseline. */
    public static boolean release(MinecraftServer server, UUID companion, String sessionToken, String reason) {
        Entry e = ENTRIES.get(companion);
        if (e == null || e.lease() == null) return false;
        if (sessionToken == null || !sessionToken.equals(e.lease().sessionToken())) return false;
        UUID owner = e.lease().ownerUuid();
        String label = e.lease().label();
        ControlState baseline = e.baseline();
        // Mark the lease dead BEFORE running the handover hook (design doc, failureHandling, step 1): the
        // hook cancels the task queue and completes in-flight futures, and either can synchronously
        // re-enter authorize()/effective() (a completed future's continuation, a cancelled task's callback).
        // If ENTRIES still showed the old lease during that window, a reentrant call could be authorized
        // against a lease that is, from this call's point of view, already gone.
        ENTRIES.put(companion, new Entry(baseline, null));
        handoverHook.handover(server, companion, blankToDefault(reason, "released"));
        logTransition(companion, ControlState.EXTERNAL, baseline, label, reason);
        syncToOwnerIfOnline(server, owner);
        return true;
    }

    /** The human override: no token needed, always wins. Used by the owner panel's "Release" button,
     *  the new logout hook, and permanent dismissal. No-op when nothing is currently leased. */
    public static void forceRelease(MinecraftServer server, UUID companion, String reason) {
        Entry e = ENTRIES.get(companion);
        if (e == null || e.lease() == null) return;
        UUID owner = e.lease().ownerUuid();
        String label = e.lease().label();
        ControlState baseline = e.baseline();
        // Same reentrancy reasoning as release(): clear the lease before the hook runs.
        ENTRIES.put(companion, new Entry(baseline, null));
        handoverHook.handover(server, companion, blankToDefault(reason, "force released"));
        logTransition(companion, ControlState.EXTERNAL, baseline, label, reason);
        syncToOwnerIfOnline(server, owner);
    }

    /** Force-release every lease owned by {@code ownerUuid} — the owner-logout hook's job (the MCP
     *  bridge is hosted inside that client, so every lease it holds is provably gone with it). */
    public static void releaseAllOwnedBy(MinecraftServer server, UUID ownerUuid, String reason) {
        for (Map.Entry<UUID, Entry> e : new ArrayList<>(ENTRIES.entrySet())) {
            Lease lease = e.getValue().lease();
            if (lease != null && ownerUuid.equals(lease.ownerUuid())) {
                forceRelease(server, e.getKey(), reason);
            }
        }
    }

    /** Owner-chosen resting state (BUILTIN or NONE only — EXTERNAL is never a baseline, it is always
     *  derived from a live lease). Takes effect immediately when no lease is held; while a lease is
     *  live the effective state stays EXTERNAL and the new baseline is only where RELEASE or expiry
     *  lands. No {@link MinecraftServer} parameter on purpose — see {@link #cachedGameTime}; callers
     *  that hold a server (the request handler) push the resulting sync themselves. */
    public static void setBaseline(UUID companion, ControlState baseline) {
        if (baseline != ControlState.BUILTIN && baseline != ControlState.NONE) {
            throw new IllegalArgumentException("baseline must be BUILTIN or NONE, got " + baseline);
        }
        Entry e = ENTRIES.get(companion);
        Lease lease = e == null ? null : e.lease();
        ControlState from = e == null ? ControlState.BUILTIN : e.baseline();
        if (from == baseline) return;   // no-op, nothing to log
        ENTRIES.put(companion, new Entry(baseline, lease));
        if (lease != null) {
            Constants.LOG.info("[numen-control] {} baseline {} -> {} (effective stays EXTERNAL, held by {})",
                    companion, from, baseline, lease.label());
        } else {
            logTransition(companion, from, baseline, "", "owner set baseline");
        }
    }

    /** The companion is gone for good (permanent dismissal) — drop its record entirely. Callers are
     *  expected to have already {@link #forceRelease}d any live lease so the handover hook ran; this
     *  just erases the (now baseline-only) record. No {@link MinecraftServer} parameter — there is
     *  nothing left to sync a state to once the companion is forgotten. */
    public static void forget(UUID companion) {
        ENTRIES.remove(companion);
    }

    // ==================================================== authorization

    /** THE choke point every body-bound tool call (from either brain) must pass. Exactly the five
     *  rules from the design doc's state machine — no other branch. */
    public static Decision authorize(MinecraftServer server, UUID companion, UUID senderOwner, String controlToken) {
        boolean tokenPresent = controlToken != null && !controlToken.isEmpty();
        switch (effective(companion)) {
            case EXTERNAL -> {
                Lease lease = leaseOf(companion);
                if (tokenPresent && lease != null && controlToken.equals(lease.sessionToken())) {
                    renew(companion, controlToken);
                    return new Decision(true, "");
                }
                String name = displayName(server, companion);
                String label = lease == null ? "" : lease.label();
                return new Decision(false, "another brain holds " + name + " (" + label + ") — acquire it first");
            }
            case BUILTIN -> {
                if (!tokenPresent) return new Decision(true, "");
                return new Decision(false, "you do not hold this companion");
            }
            default -> {   // NONE
                return new Decision(false, "no brain is designated to drive this companion");
            }
        }
    }

    // ==================================================== per-tick sweep + sync

    /** Called once per server tick (wired beside {@code Companions.tickRespawns} in the mod's tick
     *  dispatcher): expires stale leases — but NEVER one whose task is still RUNNING, so a lease can
     *  never be shorter than the work it authorised — and resends {@link ControlStatePayload} to
     *  every owner every {@value #KEEP_ALIVE_INTERVAL_TICKS} ticks. The keep-alive is what makes sync
     *  level-triggered and self-healing: a dropped packet self-corrects on the next resend instead of
     *  being able to leave a body permanently gated. */
    public static void tick(MinecraftServer server) {
        long now = server.overworld().getGameTime();
        cachedGameTime = now;

        for (Map.Entry<UUID, Entry> e : new ArrayList<>(ENTRIES.entrySet())) {
            Lease lease = e.getValue().lease();
            if (lease == null) continue;
            if (now - lease.lastHeartbeatGameTime() <= lease.ttlTicks()) continue;      // still fresh
            if (taskRunningPredicate.test(e.getKey())) continue;   // running-task exemption — never strand mid-action
            UUID companion = e.getKey();
            UUID owner = lease.ownerUuid();
            String label = lease.label();
            ControlState baseline = e.getValue().baseline();
            // Same reentrancy reasoning as release()/forceRelease(): clear before the hook runs.
            ENTRIES.put(companion, new Entry(baseline, null));
            handoverHook.handover(server, companion, "lease expired");
            logTransition(companion, ControlState.EXTERNAL, baseline, label, "lease expired");
            syncToOwnerIfOnline(server, owner);
        }

        if (now % KEEP_ALIVE_INTERVAL_TICKS == 0) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (!(p instanceof NumenPlayer)) {   // a companion body never hosts its own control state
                    syncToOwner(server, p);
                }
            }
        }
    }

    /** Push a full {@link ControlStatePayload} snapshot to {@code owner}: every companion of theirs
     *  that is either live in the world OR dormant-but-registered (so a dormant companion still
     *  reports a control state — same reasoning as {@link Companions#syncRosterToOwner}, whose live
     *  + registry walk this mirrors). Full replacement contract; call after any transition. */
    public static void syncToOwner(MinecraftServer server, ServerPlayer owner) {
        List<ControlStatePayload.Entry> list = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p instanceof NumenPlayer a && a.isOwnedByPlayer(owner.getUUID())) {
                list.add(toPayloadEntry(a.getUUID(), server));
                seen.add(a.getUUID());
            }
        }
        for (Map.Entry<UUID, CompanionRegistry.Entry> e : CompanionRegistry.get(server).ownedBy(owner.getUUID())) {
            if (seen.add(e.getKey())) {
                list.add(toPayloadEntry(e.getKey(), server));
            }
        }
        if (list.size() > ControlStatePayload.MAX) {
            list = list.subList(0, ControlStatePayload.MAX);   // defensive cap, matches CompanionListPayload
        }
        Services.NETWORK.sendToPlayer(owner, new ControlStatePayload(server.overworld().getGameTime(), list));
    }

    // ==================================================== internals

    private static ControlStatePayload.Entry toPayloadEntry(UUID companion, MinecraftServer server) {
        Entry e = ENTRIES.get(companion);
        Lease lease = e == null ? null : e.lease();
        ControlState state = effective(companion);
        long now = server.overworld().getGameTime();
        String label = lease != null ? lease.label() : "";
        String token = lease != null ? lease.sessionToken() : "";
        int ttlRemaining = lease != null
                ? (int) Math.max(0L, lease.ttlTicks() - (now - lease.lastHeartbeatGameTime())) : 0;
        int idle = lease != null ? (int) Math.max(0L, now - lease.lastHeartbeatGameTime()) : 0;
        return new ControlStatePayload.Entry(companion, (byte) state.ordinal(), label, token, ttlRemaining, idle);
    }

    private static void syncToOwnerIfOnline(MinecraftServer server, UUID ownerUuid) {
        if (ownerUuid == null) return;
        ServerPlayer owner = server.getPlayerList().getPlayer(ownerUuid);
        if (owner != null) syncToOwner(server, owner);
    }

    private static long gameTime(MinecraftServer server) {
        return server.overworld().getGameTime();
    }

    private static long clampTtl(int ttlSeconds) {
        long requested = ttlSeconds > 0 ? ttlSeconds * 20L : DEFAULT_TTL_TICKS;
        return Math.max(MIN_TTL_TICKS, Math.min(MAX_TTL_TICKS, requested));
    }

    private static String blankToDefault(String reason, String fallback) {
        return reason == null || reason.isBlank() ? fallback : reason;
    }

    /** {@code <name>} for the "another brain holds it" refusal — the live body's name, else the
     *  registry's, else the bare UUID (should never happen: EXTERNAL implies a lease was acquired,
     *  which happens only for a companion the caller already resolved a name for). */
    private static String displayName(MinecraftServer server, UUID companion) {
        NumenPlayer live = NumenPlayer.findByUuid(server, companion);
        if (live != null) return live.getName().getString();
        CompanionRegistry.Entry e = CompanionRegistry.get(server).find(companion);
        return e != null ? e.name() : companion.toString();
    }

    private static void logTransition(UUID companion, ControlState from, ControlState to,
                                       String controllerLabel, String reason) {
        Constants.LOG.info("[numen-control] {} {} -> {} (controller={}, reason={})",
                companion, from, to, controllerLabel, reason);
    }
}
