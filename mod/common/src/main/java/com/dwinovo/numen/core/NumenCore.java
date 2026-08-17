package com.dwinovo.numen.core;

import com.dwinovo.numen.core.tool.CoreServerTools;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.entity.CompanionLifecycle;
import com.dwinovo.numen.platform.Services;
import com.dwinovo.numen.core.net.CancelTasksPayload;
import com.dwinovo.numen.core.net.ExecuteToolPayload;
import com.dwinovo.numen.core.net.TaskResultPayload;
import com.dwinovo.numen.core.task.CompanionTaskFactory;
import com.dwinovo.numen.core.task.CompanionTickDispatcher;
import com.dwinovo.numen.core.task.BreakBlockCompanionTask;
import com.dwinovo.numen.core.task.BreakBlockTaskRecord;
import com.dwinovo.numen.core.task.CollectItemsTaskGoal;
import com.dwinovo.numen.core.task.CollectItemsTaskRecord;
import com.dwinovo.numen.core.task.DropCompanionTask;
import com.dwinovo.numen.core.task.DropItemsTaskRecord;
import com.dwinovo.numen.core.task.EatCompanionTask;
import com.dwinovo.numen.core.task.EatItemTaskRecord;
import com.dwinovo.numen.core.task.EquipCompanionTask;
import com.dwinovo.numen.core.task.EquipTaskRecord;
import com.dwinovo.numen.core.task.HuntCompanionTask;
import com.dwinovo.numen.core.task.HuntTaskRecord;
import com.dwinovo.numen.core.task.InteractAtCompanionTask;
import com.dwinovo.numen.core.task.InteractAtTaskRecord;
import com.dwinovo.numen.core.task.InteractEntityCompanionTask;
import com.dwinovo.numen.core.task.InteractEntityTaskRecord;
import com.dwinovo.numen.core.task.LocateBiomeTaskGoal;
import com.dwinovo.numen.core.task.LocateBiomeTaskRecord;
import com.dwinovo.numen.core.task.LocateStructureTaskGoal;
import com.dwinovo.numen.core.task.LocateStructureTaskRecord;
import com.dwinovo.numen.core.task.MineBlockTaskRecord;
import com.dwinovo.numen.core.task.MineCompanionTask;
import com.dwinovo.numen.core.task.MoveToCompanionTask;
import com.dwinovo.numen.core.task.MoveToTaskRecord;
import com.dwinovo.numen.core.task.PlaceBlockCompanionTask;
import com.dwinovo.numen.core.task.PlaceBlockTaskRecord;
import com.dwinovo.numen.core.task.ShootCompanionTask;
import com.dwinovo.numen.core.task.ShootTaskRecord;
import com.dwinovo.numen.core.task.WaitCompanionTask;
import com.dwinovo.numen.core.task.WaitTaskRecord;

/**
 * Loader-agnostic init for the {@code numen-core} tool pack — the worked example
 * of how a mod adds tools to the {@code numen-api} engine. Each loader entry
 * point calls {@link #init()} once (on both sides: a dedicated server runs the
 * task bodies), then registers its own server-tick hooks for the tools that need
 * per-tick server work (scans, the pathfinder caches).
 *
 * <p>Two things plug into the engine here:
 * <ul>
 *   <li>tools — each a {@link com.dwinovo.numen.agent.tool.NumenTool} (raw) and
 *       added to the global {@link ToolRegistry} (order preserved for prompt
 *       caching);</li>
 *   <li>task runners — each {@code TaskRecord} type a world-action tool emits is
 *       paired with the {@code CompanionTask} that runs it, via
 *       {@link CompanionTaskFactory#register}.</li>
 * </ul>
 */
public final class NumenCore {

    private static boolean initialised = false;

    private NumenCore() {}

    public static void init() {
        if (initialised) return;
        initialised = true;
        registerTools();
        registerTaskRunners();
        registerTransport();
        // L3 immediate perception (Wave B): subscribe the PerceptionEvents seams +
        // lifecycle cleanup. The poll pass is registered per-loader (see loader entries).
        com.dwinovo.numen.core.perception.Perceptions.register();
        // §8 system-generated corrective notices (numen-context-design-v1.md): a SECOND
        // CompanionLifecycle.onDeath subscriber (alongside CompanionTickDispatcher::clearActiveTask
        // below) drives the same-cause death stop-loss; the repeated-failure detector is invoked
        // from CompanionTickDispatcher.drainResults. Mechanical, LLM-free — 凡承重,必机械.
        com.dwinovo.numen.core.perception.CorrectiveNotices.register();
        Constants.LOG.info("[numen-core] registered {} tool(s), {} task type(s)",
                ToolRegistry.size(), CompanionTaskFactory.size());
    }

    /**
     * Core's own server-side execution wiring — none of it is the engine's: our
     * three transport packets (client ships a body-bound tool, server replies),
     * and the engine's {@link CompanionLifecycle} seam used to finalize our
     * per-companion tasks on death / removal / owner-abort.
     */
    private static void registerTransport() {
        Services.NETWORK.registerClientToServer(
                ExecuteToolPayload.TYPE, ExecuteToolPayload.STREAM_CODEC, ExecuteToolPayload::handle);
        Services.NETWORK.registerServerToClient(
                TaskResultPayload.TYPE, TaskResultPayload.STREAM_CODEC, TaskResultPayload::handle);
        Services.NETWORK.registerClientToServer(
                CancelTasksPayload.TYPE, CancelTasksPayload.STREAM_CODEC, CancelTasksPayload::handle);

        CompanionLifecycle.onDeath(CompanionTickDispatcher::clearActiveTask);
        CompanionLifecycle.onRemove(CompanionTickDispatcher::onCompanionRemoved);
        CompanionLifecycle.onAbort(CoreServerTools::abort);

        // W6 item 4: wire the two callback slots com.dwinovo.numen.entity.ControlRegistry (engine)
        // declared but left as no-ops — it must not import mod/ code, so this one-line registration
        // from core's own hub is the seam. Without this the lease-expiry sweep can never see a RUNNING
        // task (every lease would silently expire out from under a moving body) and a handover (release
        // / expiry / force-release / dismissal) would never cancel the mod's task queue, stranding
        // whatever tool_call ids were parked on the losing brain instead of answering them.
        com.dwinovo.numen.entity.ControlRegistry.setTaskRunningPredicate(CompanionTickDispatcher::hasRunningTask);
        com.dwinovo.numen.entity.ControlRegistry.setHandoverHook(CompanionTickDispatcher::onControlHandover);
    }

    private static void registerTools() {

        // Registration ORDER is preserved (backends with prompt-caching keyed off
        // the tool list cache stably across requests).
        ToolRegistry.register(new com.dwinovo.numen.core.tools.MoveToTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.HuntTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.ShootTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.LocateStructureTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.LocateBiomeTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.CollectItemsTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.AutoMineTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.EquipItemTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.PlaceBlockTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.BreakBlockTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.InteractAtTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.InteractEntityTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.EatItemTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.WaitTool());   // SAMPLE: raw NumenTool, no @NumenAction
        ToolRegistry.register(new com.dwinovo.numen.core.tools.DropItemsTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.InspectGuiTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.TransferTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.CloseGuiTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.GetSelfStatusTool());   // SAMPLE: raw NumenTool
        ToolRegistry.register(new com.dwinovo.numen.core.tools.GetOwnerStatusTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.LookupRecipeTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.ScanNearbyEntitiesTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.ScanBlocksTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.InspectBlockTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.InspectBlockStorageTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.GetWorldInfoTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.TodoWriteTool());   // raw NumenTool
        ToolRegistry.register(new com.dwinovo.numen.core.tools.LoadSkillTool());   // raw NumenTool

        // DERIVATIVE ADDITION (ours, not upstream): appended LAST so existing tool
        // ordering — and thus prompt caching — stays stable.
        ToolRegistry.register(new com.dwinovo.numen.core.tools.RunCommandTool());

        // L2 regional observation (Wave C): recall remembered region cognition. Appended
        // after RunCommandTool so all prior tool ordering — and prompt caching — stays stable.
        ToolRegistry.register(new com.dwinovo.numen.core.tools.RecallRegionTool());

        // §7 INFERENCE ledger (numen-context-design-v1.md): explicitly commit an important
        // model inference as a persistent cognition event. Appended LAST, after
        // RecallRegionTool, so all prior tool ordering — and prompt caching — stays stable.
        ToolRegistry.register(new com.dwinovo.numen.core.tools.CommitInferenceTool());

        // Engagement engine (§3.1 consumer c): brain-facing front door to the deterministic combat
        // assessment. Appended LAST, after CommitInferenceTool, so all prior tool ordering — and prompt
        // caching — stays stable.
        ToolRegistry.register(new com.dwinovo.numen.core.tools.AssessThreatTool());

        // Motor-skill library v1 (docs/motor-skills-v1.md): deterministic, code-closed physical
        // muscles the brain orders in one call — pillar up, bridge across, escape a shaft to the
        // surface. Appended LAST, after AssessThreatTool, so all prior tool ordering — and prompt
        // caching — stays stable.
        ToolRegistry.register(new com.dwinovo.numen.core.tools.PillarUpTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.BridgeToTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.EscapeToSurfaceTool());

        // Survival-closure v2 (刀③): turtle_up — burrow + cap + hunker down, the brain-orderable face of the
        // TurtleDrive muscle the reflex layer also fires for a CORNERED companion. Appended LAST so all prior
        // tool ordering — and prompt caching — stays stable.
        ToolRegistry.register(new com.dwinovo.numen.core.tools.TurtleUpTool());

        // L1 landmark naming (Wave D): remember_place / forget_place / recall_places — let the model name and
        // annotate important locations and recall them by name (writes the LandmarkStore's label/category/note
        // via the AgentLoopRegistry seam). Appended LAST, after TurtleUpTool, so all prior tool ordering — and
        // prompt caching — stays stable.
        ToolRegistry.register(new com.dwinovo.numen.core.tools.RememberPlaceTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.ForgetPlaceTool());
        ToolRegistry.register(new com.dwinovo.numen.core.tools.RecallPlacesTool());
    }


    private static void registerTaskRunners() {
        CompanionTaskFactory.register(MoveToTaskRecord.class, (p, r) -> new MoveToCompanionTask(p, r));
        CompanionTaskFactory.register(MineBlockTaskRecord.class, (p, r) -> new MineCompanionTask(p, r));
        CompanionTaskFactory.register(EquipTaskRecord.class, (p, r) -> new EquipCompanionTask(p, r));
        CompanionTaskFactory.register(WaitTaskRecord.class, (p, r) -> new WaitCompanionTask(p, r));
        CompanionTaskFactory.register(DropItemsTaskRecord.class, (p, r) -> new DropCompanionTask(p, r));
        CompanionTaskFactory.register(BreakBlockTaskRecord.class, (p, r) -> new BreakBlockCompanionTask(p, r));
        CompanionTaskFactory.register(EatItemTaskRecord.class, (p, r) -> new EatCompanionTask(p, r));
        CompanionTaskFactory.register(HuntTaskRecord.class, (p, r) -> new HuntCompanionTask(p, r));
        CompanionTaskFactory.register(ShootTaskRecord.class, (p, r) -> new ShootCompanionTask(p, r));
        CompanionTaskFactory.register(CollectItemsTaskRecord.class, (p, r) -> new CollectItemsTaskGoal(p, r));
        CompanionTaskFactory.register(PlaceBlockTaskRecord.class, (p, r) -> new PlaceBlockCompanionTask(p, r));
        CompanionTaskFactory.register(InteractAtTaskRecord.class, (p, r) -> new InteractAtCompanionTask(p, r));
        CompanionTaskFactory.register(InteractEntityTaskRecord.class, (p, r) -> new InteractEntityCompanionTask(p, r));
        CompanionTaskFactory.register(LocateStructureTaskRecord.class, (p, r) -> new LocateStructureTaskGoal(p, r));
        CompanionTaskFactory.register(LocateBiomeTaskRecord.class, (p, r) -> new LocateBiomeTaskGoal(p, r));

        // Motor-skill library v1 (fully-qualified to keep this a purely additive change).
        CompanionTaskFactory.register(com.dwinovo.numen.core.task.PillarUpTaskRecord.class,
                (p, r) -> new com.dwinovo.numen.core.task.PillarUpCompanionTask(p, r));
        CompanionTaskFactory.register(com.dwinovo.numen.core.task.BridgeToTaskRecord.class,
                (p, r) -> new com.dwinovo.numen.core.task.BridgeToCompanionTask(p, r));
        CompanionTaskFactory.register(com.dwinovo.numen.core.task.EscapeToSurfaceTaskRecord.class,
                (p, r) -> new com.dwinovo.numen.core.task.EscapeToSurfaceCompanionTask(p, r));

        // Survival-closure v2 (刀③): the turtle_up muscle's task runner.
        CompanionTaskFactory.register(com.dwinovo.numen.core.task.TurtleUpTaskRecord.class,
                (p, r) -> new com.dwinovo.numen.core.task.TurtleUpCompanionTask(p, r));
    }
}
