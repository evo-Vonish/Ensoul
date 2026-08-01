package com.dwinovo.numen.core.task;
import com.dwinovo.numen.task.TaskResult;

/**
 * One running task on a companion {@link com.dwinovo.numen.entity.NumenPlayer}
 * body, driven by {@code CompanionTickDispatcher}. The player-body replacement
 * for the Mob's {@code LlmTaskGoal} (which was a vanilla {@code Goal} run by a
 * GoalSelector) — here the dispatcher owns the lifecycle directly:
 * {@link #start()} once, {@link #tick()} each server tick until it returns a
 * terminal {@link TaskState}, then {@link #buildResult} for the reply.
 */
public interface CompanionTask {

    /** First-tick setup. May return a terminal state immediately via the record. */
    void start();

    /** Advance one tick. Returns {@link TaskState#RUNNING} or a terminal state. */
    TaskState tick();

    /** The result envelope handed back to the LLM. */
    TaskResult buildResult(TaskState finalState);

    /**
     * 一句话进度("我做到哪了"),在结算时刻实时读取。所有非 SUCCESS 的
     * TaskResult 都编织此串,保证 TIMEOUT/CANCELLED 永远报告推进到哪;FAILED
     * 分支若 doneReason 本身已含富进度(如 mine 的 terminalMessage、move_to 的
     * blockedResult)则保留 doneReason 为主,不重复追加。
     * 契约不变量:start() 早退、tick() 从未跑过时也必须安全返回(报告零/初始
     * 状态,如 "尚未离开起点 y=64"),不得 NPE、不得读未初始化字段。
     */
    String progressSummary();
}
