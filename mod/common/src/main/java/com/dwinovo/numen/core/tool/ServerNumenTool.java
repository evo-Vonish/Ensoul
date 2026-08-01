package com.dwinovo.numen.core.tool;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.agent.tool.api.ToolContext;
import com.dwinovo.numen.core.task.CompanionTickDispatcher;
import com.dwinovo.numen.core.task.TaskRecord;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonObject;

import java.util.function.Consumer;

/**
 * Convenience base for a numen-core tool that runs on the companion body
 * (server-side). This is <em>optional sugar that lives in core</em>, not part of
 * the engine — the engine's only contract is the raw {@link NumenTool}.
 *
 * <p>It wires the one bit of boilerplate every body-bound tool shares:
 * {@link #invoke} ships the call to the server through core's own transport
 * ({@link CoreServerTools#ship}) and parks it; core's {@code ExecuteToolPayload}
 * handler then calls {@link #runOnServer} on the live body. There the tool does
 * whatever it wants — reply immediately (a query) or enqueue a task and let the
 * task lifecycle reply later (a world action).
 *
 * <p>A tool that wants something this doesn't fit just implements
 * {@link NumenTool} directly and sends its own packets.
 */
public abstract class ServerNumenTool implements NumenTool {

    @Override
    public final void invoke(ToolCall call) {
        CoreServerTools.ship(call);   // client side: ship to the body, park the call until the result returns
    }

    /** Runs on the server with the live companion body. Reply now, or enqueue a task and reply later. */
    public abstract void runOnServer(String toolCallId, JsonObject args,
                                     NumenPlayer companion, Consumer<String> reply);

    /**
     * Per-call {@code max_seconds} cap, bound by the {@code ExecuteToolPayload} dispatch
     * chokepoint just before {@link #runOnServer} and read by {@link #ctx}. This ambient
     * hand-off keeps the cap out of every tool's {@code ctx(...)} call site (they'd all
     * need editing otherwise): the whole dispatch → runOnServer → ctx() chain runs
     * synchronously on the server tick thread, so a plain thread-local is exact and leak-free.
     */
    private static final ThreadLocal<Integer> PENDING_MAX_SECONDS = new ThreadLocal<>();

    /** Bind this call's model-requested {@code max_seconds} ({@code <=0} = none). Dispatch chokepoint only. */
    public static void bindMaxSeconds(int maxSeconds) {
        if (maxSeconds > 0) PENDING_MAX_SECONDS.set(maxSeconds);
        else PENDING_MAX_SECONDS.remove();
    }

    /** Clear the bound {@code max_seconds} once the call returns. Always paired with {@link #bindMaxSeconds} in a finally. */
    public static void unbindMaxSeconds() {
        PENDING_MAX_SECONDS.remove();
    }

    /** Helper for world-action tools: a ToolContext carrying the call id, the body's current game time, and any max_seconds cap. */
    protected static ToolContext ctx(String toolCallId, NumenPlayer companion) {
        Integer cap = PENDING_MAX_SECONDS.get();
        return new ToolContext(toolCallId, companion.level().getGameTime(), cap == null ? 0 : cap);
    }

    /** Helper for world-action tools: hand a built task record to the companion's queue. */
    protected static void enqueue(NumenPlayer companion, TaskRecord record) {
        CompanionTickDispatcher.queueFor(companion.getUUID()).enqueue(record);
    }
}

