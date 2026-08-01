package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.client.agent.AgentLoopRegistry;
import com.dwinovo.numen.core.tool.Schema;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;

import java.util.Map;

/**
 * Client-local tool (raw NumenTool): forget a place the companion previously named with
 * {@code remember_place} — the counterpart to {@link RememberPlaceTool} in the L1 landmark naming set
 * (Wave D). Given the place's name (label) or its {@code lm_xxx} id, the engine drops the landmark and
 * emits a {@code removed} event through the existing landmark machinery.
 *
 * <p>Pure client-local, mirroring {@link CommitInferenceTool}: answered synchronously via
 * {@link AgentLoopRegistry#forgetPlace}, keyed by the companion identity on the call
 * ({@code call.ctx().entityUuid()}).
 */
public final class ForgetPlaceTool implements NumenTool {

    private static final Gson GSON = new Gson();

    private record Args(String place) {}

    @Override
    public String name() {
        return "forget_place";
    }

    @Override
    public String description() {
        return "Forget a place you previously named with remember_place — give its name (label) or its "
                + "lm_xxx id. Use this when a base is abandoned or a landmark no longer matters, to keep your "
                + "remembered places tidy.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("place", "The name (label) or lm_xxx id of the place to forget.")
                .build();
    }

    @Override
    public void invoke(ToolCall call) {
        try {
            Args a = GSON.fromJson(call.rawArgs(), Args.class);
            String place = a == null ? null : a.place();
            // The engine seam validates (blank input, no match, dead body) and returns the confirmation JSON.
            call.complete(AgentLoopRegistry.forgetPlace(call.ctx().entityUuid(), place));
        } catch (RuntimeException ex) {
            call.complete(TaskResult.fail(ex.getMessage()).toJson());
        }
    }
}
