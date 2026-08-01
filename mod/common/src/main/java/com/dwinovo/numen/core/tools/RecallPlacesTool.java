package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.client.agent.AgentLoopRegistry;
import com.dwinovo.numen.core.tool.Schema;
import com.dwinovo.numen.task.TaskResult;

import java.util.Map;

/**
 * Client-local tool (raw NumenTool): list every place the companion has named with {@code remember_place},
 * grouped by dimension, with coordinates, category and notes — the read half of the L1 landmark naming set
 * (Wave D). This is on-demand recall of the owner-given names, distinct from the periodic context snapshot.
 *
 * <p>Pure client-local, mirroring {@link CommitInferenceTool}: answered synchronously via
 * {@link AgentLoopRegistry#listPlaces}, keyed by the companion identity on the call
 * ({@code call.ctx().entityUuid()}). Takes no arguments; returns the landmark list as plain text.
 */
public final class RecallPlacesTool implements NumenTool {

    @Override
    public String name() {
        return "recall_places";
    }

    @Override
    public String description() {
        return "List every place you have named (with remember_place), grouped by dimension, with coordinates, "
                + "category and notes. Use it to remember where your named locations are and recall a place by "
                + "name before heading there.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.none();
    }

    @Override
    public void invoke(ToolCall call) {
        try {
            // No arguments; the engine seam returns the current landmark list as text (or a "nothing yet" line).
            call.complete(AgentLoopRegistry.listPlaces(call.ctx().entityUuid()));
        } catch (RuntimeException ex) {
            call.complete(TaskResult.fail(ex.getMessage()).toJson());
        }
    }
}
