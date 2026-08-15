package com.dwinovo.numen.core.task;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.look.LookBrain;
import com.dwinovo.numen.core.net.TaskResultPayload;
import com.dwinovo.numen.platform.Services;
import com.dwinovo.numen.task.TaskResult;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * numen-core's server-tick driver of companion tasks. The engine ({@code numen-api})
 * owns the body and is a pure scheduler; <em>task execution</em> is core's, so the
 * per-companion task queue lives here (keyed by companion UUID), not on the body.
 *
 * <p>Each tick, for every live {@link NumenPlayer}: pull the head of its queue,
 * run the matching {@link CompanionTask} to completion (deadline-bounded), and
 * ship finished results back to the owner as {@link TaskResultPayload} — core's
 * own packet. Registered from core's end-of-tick hooks; finalised on body
 * removal / death / owner-abort via the engine's {@code CompanionLifecycle} seam.
 */
@com.dwinovo.numen.api.Internal
public final class CompanionTickDispatcher {

    private record Running(CompanionTask task, TaskRecord record) {}

    private static final Map<UUID, Running> ACTIVE = new HashMap<>();
    /** Per-companion task queue — replaces the body-hosted queue the engine no longer keeps. */
    private static final Map<UUID, TaskQueue> QUEUES = new HashMap<>();

    private CompanionTickDispatcher() {}

    /** The companion's task queue (created on first use). Body-bound tools enqueue here. */
    public static TaskQueue queueFor(UUID companionUuid) {
        return QUEUES.computeIfAbsent(companionUuid, k -> new TaskQueue());
    }

    public static void tick(MinecraftServer server) {
        com.dwinovo.numen.entity.Companions.tickRespawns(server);   // timed death recoveries
        // Same per-tick sweep slot (W6): expire stale leases (never one whose task is still RUNNING —
        // see the touch() call in tickOne below) and resend ControlStatePayload on the keep-alive
        // cadence. Lives beside tickRespawns for the same reason: both are cheap, unconditional,
        // once-per-tick maintenance the dispatcher already owns the loop for.
        com.dwinovo.numen.entity.ControlRegistry.tick(server);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p instanceof NumenPlayer ap) {
                // Per-companion isolation, same shape as Perceptions::tick: an exception out of one
                // companion's task used to unwind the whole server tick — one tool bug crashed the
                // server for everyone. Contain it here and fail just that task.
                try {
                    tickOne(ap);
                    // Lively-look attention brain: pick what this companion looks at and feed the body's
                    // look engine a smooth gaze intent. Runs every tick (idle AND walking); the engine
                    // itself yields to any fresher hard-aim / locomotion, so this can push unconditionally.
                    LookBrain.tick(ap);
                    // GUI-engagement posture ("工位姿态"): while a container menu is open, face the station
                    // and stay planted for the whole session. Runs AFTER tickOne (so its halt overrides the
                    // idle-autonomy sleepwalk that tickOne may have issued) and BEFORE the reflex layer's
                    // Perceptions::tick (so a survival reflex still wins the body — see GuiEngagement).
                    com.dwinovo.numen.core.perception.GuiEngagement.tick(ap);
                } catch (Throwable t) {
                    failCrashedTask(ap, t);
                }
            }
        }
    }

    /**
     * A companion's tick threw. Isolate it: drop the offending task and ship a FAILED result.
     *
     * <p>Failing the record matters as much as catching the throw. Left RUNNING, a deterministic
     * task bug re-throws every tick forever while the model sits blocked on a {@code tool_call}
     * id that will never be answered — a caught crash that repeats is barely better than one that
     * takes the server down.
     *
     * <p>Builds the result directly rather than through {@code task.buildResult()}: that is the
     * same task code that just crashed, and calling back into it here risks a second throw on the
     * recovery path.
     */
    private static void failCrashedTask(NumenPlayer player, Throwable t) {
        UUID id = player.getUUID();
        Constants.LOG.error("[numen-core] companion task tick failed for {}", id, t);
        Running running = ACTIVE.remove(id);
        if (running != null) {
            running.record().setState(TaskState.FAILED);
            running.record().setResult(TaskResult.fail("task crashed: " + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage())));
            queueFor(id).complete(running.record());
        }
        try {
            drainResults(player);
        } catch (Throwable t2) {
            Constants.LOG.error("[numen-core] shipping results after a task crash failed for {}", id, t2);
        }
    }

    /**
     * Drop a companion's running task WITHOUT shipping a result — used on death, where the client's
     * {@code NumenDeathPayload} already resolves the in-flight tool call with the death cause (so a
     * second result here would be a duplicate the client ignores).
     */
    public static void clearActiveTask(NumenPlayer player) {
        ACTIVE.remove(player.getUUID());
    }

    /** Owner pressed Stop: cancel the running task and drop the queue for this companion. */
    public static void cancelFor(NumenPlayer player) {
        cancelWithCause(player.getUUID(), "interrupted by owner");
    }

    /**
     * The design doc's shared "handover hook" (failureHandling section), step 2: control changed hands
     * (release / expiry / force-release / dismissal) — cancel whatever is running and drain the queue
     * with a cause that NAMES the handover, so every parked {@code tool_call} id gets an honest answer
     * instead of dead-waiting on a brain that no longer holds the body, instead of the generic "interrupted
     * by owner" {@link #cancelFor} uses for the human Stop button. Keyed by UUID, not {@link NumenPlayer},
     * because a lease can be released while its companion is dormant (not currently spawned) — nothing
     * would be RUNNING in that case, but the queue is still cleared for consistency.
     *
     * <p>This class cannot register itself as the hook — {@code ControlRegistry} (engine) must not import
     * {@code mod/} code, so the actual {@code ControlRegistry.setHandoverHook} call is a one-line addition
     * to {@code NumenCore}'s registration hub, wiring {@code CompanionTickDispatcher::onControlHandover}
     * as the method reference (signature-compatible with {@code ControlRegistry.HandoverHook}). See this
     * wave's followUps for the exact line — {@code NumenCore.java} is outside this file set.
     */
    public static void onControlHandover(MinecraftServer server, UUID companion, String reason) {
        cancelWithCause(companion, "control handover: " + reason);
        Constants.LOG.info("[numen-core] control handover for {}: {}", companion, reason);
    }

    /**
     * {@code ControlRegistry}'s task-running predicate (registered the same way as {@link
     * #onControlHandover} — see its javadoc): true while a task is RUNNING for {@code companion}, so the
     * lease-expiry sweep in {@code ControlRegistry.tick} never strands a moving body mid-action. Method
     * reference {@code CompanionTickDispatcher::hasRunningTask} is signature-compatible with {@code
     * Predicate<UUID>}.
     */
    public static boolean hasRunningTask(UUID companion) {
        Running running = ACTIVE.get(companion);
        return running != null && running.record().getState() == TaskState.RUNNING;
    }

    /** Shared cancel body for {@link #cancelFor} and {@link #onControlHandover} — only the cause differs. */
    private static void cancelWithCause(UUID id, String cause) {
        queueFor(id).cancelAll(cause);
        Running running = ACTIVE.get(id);
        if (running != null && running.record().getState() == TaskState.RUNNING) {
            running.record().setState(TaskState.CANCELLED);
        }
    }

    /**
     * Finalize a companion's running task because the BODY is leaving the world
     * (dormancy / dismissal / death) — the tick loop only visits players still in
     * the player list, so without this the running task is orphaned in {@link
     * #ACTIVE} and its {@code buildResult} side-effects never run (e.g. a mining
     * dig's crack overlay would stay painted on every viewer until chunk reload).
     */
    public static void onCompanionRemoved(NumenPlayer player) {
        UUID id = player.getUUID();
        Running running = ACTIVE.remove(id);
        if (running != null) {
            TaskState st = running.record().getState();
            if (st == TaskState.RUNNING || st == TaskState.PENDING) {
                st = TaskState.CANCELLED;
                running.record().setState(st);
            }
            running.record().setResult(running.task().buildResult(st));
            queueFor(id).complete(running.record());
        }
        // Everything still QUEUED behind the running task needs an answer too. Dropping QUEUES
        // below stranded them silently: each carries a tool_call id the client's agent loop is
        // blocked on, so the companion simply stopped responding — no error, no result, ever.
        // cancelAll moves them to the outbox; drain (now unconditional — cancellations produce
        // results even when nothing was running) actually ships them.
        queueFor(id).cancelAll("companion removed from world");
        drainResults(player);
        QUEUES.remove(id);   // the body is gone; don't leak its queue
        LookBrain.forget(id);   // drop the companion's attention state with its queue
        com.dwinovo.numen.core.perception.GuiEngagement.forget(id);   // ...and any GUI-engagement registration
    }

    private static void tickOne(NumenPlayer player) {
        UUID id = player.getUUID();
        Running running = ACTIVE.get(id);

        if (running == null) {
            // Control gate (W4/gatedSites): a task may only START while some brain is designated to
            // drive this body (BUILTIN or EXTERNAL) — never at baseline NONE. Ownership answers "whose
            // companion is this"; control answers "which brain may drive it right now", and the two are
            // independent. Gate TASK START ONLY: the RUNNING branch below is deliberately left ungated —
            // a task already accepted must reach a terminal state or the body is stranded mid-motion.
            // Cross-brain leftovers (a task still RUNNING when control changes hands) are handled by the
            // handover hook (onControlHandover), not by a per-tick gate here. A task blocked here simply
            // stays queued: it is not dropped, and starts the moment control is designated again.
            if (com.dwinovo.numen.entity.ControlRegistry.effective(id)
                    != com.dwinovo.numen.entity.ControlRegistry.ControlState.NONE) {
                TaskRecord rec = queueFor(id).pollHead();
                if (rec != null) {
                    rec.setState(TaskState.RUNNING);
                    CompanionTask task = CompanionTaskFactory.create(player, rec);
                    running = new Running(task, rec);
                    ACTIVE.put(id, running);
                    task.start();   // may flip the record terminal immediately
                }
            }
        } else if (running.record().getState() == TaskState.RUNNING) {
            if (player.level().getGameTime() >= running.record().getDeadlineGameTime()) {
                running.record().setState(TaskState.TIMEOUT);
            } else if (!com.dwinovo.numen.core.perception.Reflexes.ownsBody(player)) {
                // 身体仲裁 (刀②): while a survival reflex drives the body (flee-nav / turtle / creeper sprint /
                // drowning / fire-lava) the task yields — its navigator must not fight the reflex for the inputs.
                running.record().setState(running.task().tick());
            }
        }

        // Finish on any terminal state (set by start(), tick(), deadline, or cancel).
        running = ACTIVE.get(id);
        if (running != null) {
            TaskState st = running.record().getState();
            if (st != TaskState.RUNNING && st != TaskState.PENDING) {
                running.record().setResult(running.task().buildResult(st));
                queueFor(id).complete(running.record());
                ACTIVE.remove(id);
            } else if (st == TaskState.RUNNING) {
                // Task-attributed lease renewal (W6): a lease must never expire while a task it
                // authorised is still RUNNING, or the timeout hands a MOVING body back to the built-in
                // brain mid-action — worse than no timeout at all. ControlRegistry.touch() is a no-op
                // when no lease is held (a BUILTIN-driven task), so this call is always safe to make
                // unconditionally. It is also always CORRECT to attribute this touch to whichever lease
                // is currently live, without threading an explicit token through TaskRecord: acquire()
                // is compare-and-set (refuses a second lease while one is already live) and every
                // release / expiry / force-release runs the handover hook — which cancels THIS running
                // task via onControlHandover — strictly BEFORE a new lease can ever be acquired for this
                // companion. So a task that is still RUNNING here can only ever belong to whichever lease
                // is live right now, if any; there is no window in which a stale lease's touch could leak
                // onto a different lease's task.
                com.dwinovo.numen.entity.ControlRegistry.touch(id);
            }
        }

        // Idle-autonomy L3 (sleepwalk layer, lowest priority): self-gates on reflex body
        // ownership / brain turn in flight, and we hand it the dispatcher's own ground truth
        // about an active task, so it acts only when nobody else is driving. It runs after
        // the completion block so a task that just finished frees the body this same tick.
        // See docs/idle-autonomy-L3.md.
        com.dwinovo.numen.core.autonomy.AutonomyScheduler.tick(player, ACTIVE.containsKey(id));

        drainResults(player);
    }

    private static void drainResults(NumenPlayer player) {
        // Resolve the owner BEFORE draining. drainCompleted() empties the outbox, so doing it first
        // and then bailing on a null owner destroyed every result finished while the owner was
        // offline — and the old "the loop will re-ask" was wishful: the client loop is blocked on
        // those tool_call ids and has nothing to re-ask with. Leave them queued; a reconnecting
        // owner gets them on the next tick.
        ServerPlayer owner = player.resolveOwnerPlayer();
        if (owner == null) return;
        List<TaskRecord> completed = queueFor(player.getUUID()).drainCompleted();
        if (completed.isEmpty()) return;
        for (TaskRecord rec : completed) {
            TaskResult result = rec.getResult();
            // §8 corrective-notice producer: mechanically watch for repeated identical failures on
            // this companion's server tasks before we ship the result (owner-cancellations excluded
            // inside). Placed after the owner null-check so it only counts results actually delivered.
            com.dwinovo.numen.core.perception.CorrectiveNotices.onTaskResult(player, rec.getToolName(), result);
            String json = result == null
                    ? "{\"success\":false,\"message\":\"no result produced\"}"
                    : result.toJson();
            Services.NETWORK.sendToPlayer(owner,
                    new TaskResultPayload(player.getUUID(), rec.getToolCallId(), json));
        }
    }
}
