package com.dwinovo.numen.agent.tool.api;

/**
 * Per-call framework plumbing for a server-side tool that produces a body task.
 * It carries the values such a tool can't make up itself — the originating
 * {@code tool_call} id (which must ride through to the result), the current
 * game time (the basis for a deadline), and the model-requested {@code max_seconds}
 * cap for this call ({@code 0} = none).
 *
 * <p>A query / client tool never needs this; only code that builds a task
 * record uses a {@code ToolContext}.
 *
 * <h2>The {@code max_seconds} cap</h2>
 * Every tool schema carries an optional {@code max_seconds} the model may fill to
 * time-box a single call. That value is read once at the server dispatch chokepoint
 * and threaded onto this context; {@link #deadline(long)} then tightens the task's
 * own self-estimated budget to {@code min(estimate, now + max_seconds*20)}. Because
 * every body task computes its deadline through {@link #deadline(long)}, this is the
 * single point where the cap takes effect — no per-task file needs to know about it.
 */
public record ToolContext(String toolCallId, long gameTime, int maxSeconds) {

    /** Back-compat convenience: a context with no {@code max_seconds} cap. */
    public ToolContext(String toolCallId, long gameTime) {
        this(toolCallId, gameTime, 0);
    }

    /**
     * Absolute deadline game-tick for a task that should run at most {@code ticks} ticks.
     * When the model supplied a {@code max_seconds} cap for this call, the returned
     * deadline is the tighter of the task's own estimate and the cap
     * ({@code now + maxSeconds*20}), so a time-boxed call stops on time and reports
     * its progress via the task's existing TIMEOUT narrative.
     */
    public long deadline(long ticks) {
        long taskDeadline = gameTime + ticks;
        if (maxSeconds > 0) {
            return Math.min(taskDeadline, gameTime + maxSeconds * 20L);
        }
        return taskDeadline;
    }
}
