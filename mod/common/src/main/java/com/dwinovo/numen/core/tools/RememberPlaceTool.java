package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.client.agent.AgentLoopRegistry;
import com.dwinovo.numen.core.tool.Schema;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;

import java.util.Map;

/**
 * Client-local tool (raw NumenTool): give an important location a NAME so the companion can recall it
 * later by name — the write half of the L1 landmark layer's owner-naming (Wave D). The passive harvest
 * already remembers <em>where</em> functional blocks are ("there is a furnace at …"); this tool lets the
 * model say <em>what a place means</em> ("this is my main base furnace"), writing the landmark store's
 * long-dormant {@code label}/{@code category}/{@code note} fields.
 *
 * <p>Pure client-local, mirroring {@link CommitInferenceTool}: answered synchronously via the engine's
 * pack-facing seam {@link AgentLoopRegistry#rememberPlace}, keyed by the companion identity already on the
 * call ({@code call.ctx().entityUuid()}). Naming a coordinate that already holds a remembered station
 * re-labels it in place; a fresh coordinate becomes a new named waypoint. The engine emits the resulting
 * {@code added}/{@code renamed} landmark event through the existing landmark machinery.
 */
public final class RememberPlaceTool implements NumenTool {

    private static final Gson GSON = new Gson();

    private record Args(Integer x, Integer y, Integer z, String label, String category, String note) {}

    @Override
    public String name() {
        return "remember_place";
    }

    @Override
    public String description() {
        return "Give an important location a NAME (and optional note) so you can recall it later by name — "
                + "your home base, a mine entrance, a danger spot, a friend's house. Naming a coordinate where "
                + "you already remember a station (a furnace, chest, …) re-labels that station in place; naming "
                + "a fresh coordinate creates a new named waypoint. Omit x/y/z to name the spot you are standing "
                + "on. Named places are never forgotten automatically — use recall_places to review them and "
                + "forget_place to drop one.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("label", "Short name for this place, e.g. \"main base\" or \"north mine\".")
                .nullableInteger("x", "World X of the place, or null to use where you are standing.")
                .nullableInteger("y", "World Y of the place, or null to use where you are standing.")
                .nullableInteger("z", "World Z of the place, or null to use where you are standing.")
                .nullableString("category",
                        "Optional coarse bucket, e.g. \"base\", \"resource\", \"danger\", \"home\". Null if unsure.")
                .nullableString("note",
                        "Optional free-form note about this place, e.g. \"chest with spare tools here\". Null if none.")
                .build();
    }

    @Override
    public void invoke(ToolCall call) {
        try {
            Args a = GSON.fromJson(call.rawArgs(), Args.class);
            if (a == null || a.label() == null || a.label().isBlank()) {
                call.complete(TaskResult.fail("remember_place needs a non-empty label").toJson());
                return;
            }
            // The engine seam validates the rest (dead / unloaded body, coordinate default) and returns the
            // tool-result confirmation JSON carrying the landmark id.
            call.complete(AgentLoopRegistry.rememberPlace(call.ctx().entityUuid(),
                    a.x(), a.y(), a.z(), a.label(), a.category(), a.note()));
        } catch (RuntimeException ex) {
            call.complete(TaskResult.fail(ex.getMessage()).toJson());
        }
    }
}
