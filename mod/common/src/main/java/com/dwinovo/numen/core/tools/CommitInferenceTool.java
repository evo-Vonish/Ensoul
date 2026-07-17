package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.client.agent.AgentLoopRegistry;
import com.dwinovo.numen.core.tool.Schema;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;

import java.util.Map;

/**
 * Client-local tool (raw NumenTool): commit a model inference as a persistent,
 * append-only cognition event — the §7 INFERENCE ledger of
 * {@code docs/numen-context-design-v1.md}. Important conclusions must not live
 * only inside thinking blocks (a channel whose cross-turn retention varies by
 * model and is the first target of context cleaning); this tool lets the model
 * explicitly write one down. The engine appends it to the conversation tail as
 * {@code <inference provenance="inferred">…</inference>} — envelope-stamped
 * (seq/schemaVersion/gameTime) at the engine's choke point, never here — where
 * later {@code observed} events can confirm or refute it: append, never rewrite.
 *
 * <p>Pure client-local: no server body involved, mirrors {@link LoadSkillTool} —
 * the call is answered synchronously via the engine's pack-facing seam
 * {@link AgentLoopRegistry#commitInference(java.util.UUID, String)}, keyed by the
 * companion identity already carried on the call ({@code call.ctx().entityUuid()}).
 */
public final class CommitInferenceTool implements NumenTool {

    private static final Gson GSON = new Gson();

    private record Args(String inference) {}

    @Override
    public String name() {
        return "commit_inference";
    }

    @Override
    public String description() {
        return "把一条重要推断显式落账为持久认知事件(INFERENCE)。当你形成了未经直接观察、"
                + "但对后续决策重要的结论(如\"箱子疑似被玩家清空\")时调用;它会以 inferred "
                + "来源标记追加到你的认知历史,之后的实地观察可以证实或反驳它。思考过程不会被"
                + "长期保留——重要结论必须用本工具落账,否则会随草稿一起消失。";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("inference", "把你此刻的重要推断落账为持久认知事件;"
                        + "它将带 inferred 来源标记,后续观察可证实或反驳它")
                .build();
    }

    @Override
    public void invoke(ToolCall call) {
        try {
            Args a = GSON.fromJson(call.rawArgs(), Args.class);
            String text = a == null ? null : a.inference();
            // The engine seam validates (blank text, missing loop) and returns the
            // tool-result confirmation JSON carrying the assigned seq.
            call.complete(AgentLoopRegistry.commitInference(call.ctx().entityUuid(), text));
        } catch (RuntimeException ex) {
            call.complete(TaskResult.fail(ex.getMessage()).toJson());
        }
    }
}
