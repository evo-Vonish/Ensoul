package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.combat.EngagementAssessment;
import com.dwinovo.numen.core.combat.McCombatAdapter;
import com.dwinovo.numen.core.combat.ThreatNote;
import com.dwinovo.numen.core.tool.Schema;
import com.dwinovo.numen.core.tool.ServerNumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/**
 * DERIVATIVE-ADDED TOOL (ours) — brain-facing front door to the deterministic engagement engine
 * ({@link com.dwinovo.numen.core.combat.EngagementAssessor}, §3.1 output contract). Query tool (raw
 * {@link ServerNumenTool}): it scans the hostiles around the body, runs the pure no-LLM combat math
 * ({@code McCombatAdapter} → {@code EngagementAssessor}), and returns the whole {@link EngagementAssessment}
 * as JSON — the verdict (ENGAGE / KITE / FLEE / AVOID / CORNERED), how confident it is, the Chinese
 * limiting factor, the TTK/TTD numbers, the frontage {@code k}, and a per-threat breakdown.
 *
 * <p>This is <em>consumer (c)</em> in the design (the reflex layer is consumer (a), which uses the same
 * {@code McCombatAdapter} entry point on its own throttle). It replies in place, same tick, no task queue —
 * same pattern as {@link RunCommandTool} / {@link RecallRegionTool}. It is advisory: it never moves the body
 * or cancels a task; the model reads it and decides.
 */
public final class AssessThreatTool extends ServerNumenTool {

    private static final Gson GSON = new Gson();

    /** Default / clamp bounds for the hostile-gather radius when the model omits one. */
    private static final double DEFAULT_RADIUS = 16.0;
    private static final double MIN_RADIUS = 1.0;
    private static final double MAX_RADIUS = 64.0;

    /** Boxed so an omitted / null radius means "use the default". */
    private record Args(Double radius) {}

    @Override
    public String name() {
        return "assess_threat";
    }

    @Override
    public String description() {
        return "Assess whether the nearby hostile situation is winnable, using deterministic Minecraft combat "
                + "math (armor, weapon DPS, per-mob stats, Lanchester frontage) — no guessing. Returns a "
                + "verdict: ENGAGE (fight now), KITE (fight from range / behind a choke), FLEE (run — the fight "
                + "is lost), AVOID (threats exist but reroute around them), or CORNERED (can neither win nor "
                + "flee — escalate: pillar up, water, call your owner). Also returns confidence, a Chinese "
                + "limiting_factor (what would have to change to flip the verdict), ttk_clear_seconds "
                + "(time to clear reachable melee threats), ttd_me_seconds (time until you die at the current "
                + "incoming DPS), frontage_k (how many can hit you at once), and a per-threat breakdown. "
                + "Advisory only — it does not move you.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .nullableNumber("radius", "Blocks to gather hostiles over for the assessment, in [1, 64]. "
                        + "Pass null for the default (16).")
                .build();
    }

    @Override
    public void runOnServer(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        try {
            Args a = GSON.fromJson(args, Args.class);
            double radius = a == null || a.radius() == null ? DEFAULT_RADIUS
                    : Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, a.radius()));

            // The reflex layer owns the recorded-attacker memory (a provoking neutral golem / enderman); this
            // brain-facing tool does a general scan, so no provoked entity is forced in — pass null.
            EngagementAssessment as = McCombatAdapter.assessNearby(self, null, radius);
            reply.accept(toJson(as).toString());
        } catch (Exception e) {
            // A query must never crash the server tick — surface it as a failure reply.
            String msg = e.getMessage();
            JsonObject root = new JsonObject();
            root.addProperty("success", false);
            root.addProperty("message", "威胁评估失败: " + (msg == null ? e.getClass().getSimpleName() : msg));
            reply.accept(root.toString());
        }
    }

    private static JsonObject toJson(EngagementAssessment as) {
        JsonObject root = new JsonObject();
        root.addProperty("success", true);
        root.addProperty("decision", as.decision().name());
        root.addProperty("decision_cn", decisionCn(as.decision().name()));
        root.addProperty("confidence", round2(as.confidence()));
        root.addProperty("limiting_factor", as.limitingFactor());
        addNumber(root, "ttk_clear_seconds", as.ttkClear());   // seconds to clear reachable melee threats
        addNumber(root, "ttd_me_seconds", as.ttdMe());         // seconds until I die at the current incoming DPS
        root.addProperty("frontage_k", as.frontageK());
        root.addProperty("summary", summary(as));

        JsonArray threats = new JsonArray();
        for (ThreatNote n : as.perThreat()) {
            JsonObject t = new JsonObject();
            t.addProperty("id", n.id());
            t.addProperty("type", n.type());
            t.addProperty("distance", round2(n.dist()));
            t.addProperty("hp", round2(n.hp()));
            t.addProperty("threat_class", n.threatClass().name());
            t.addProperty("contributes_dps", round2(n.contributesDps()));
            t.addProperty("ranged_gated", n.rangedGated());
            t.addProperty("note", n.note());
            threats.add(t);
        }
        root.add("threats", threats);
        return root;
    }

    /** A one-line Chinese summary the model can read at a glance. */
    private static String summary(EngagementAssessment as) {
        StringBuilder sb = new StringBuilder();
        sb.append("评估结果:").append(decisionCn(as.decision().name()))
                .append("(置信度").append(round2(as.confidence())).append(")");
        if (!as.limitingFactor().isEmpty()) {
            sb.append("。制约因素:").append(as.limitingFactor());
        }
        sb.append("。清怪耗时").append(fmtSeconds(as.ttkClear()))
                .append(",可支撑").append(fmtSeconds(as.ttdMe()))
                .append(",当前受击数").append(as.frontageK()).append("。");
        return sb.toString();
    }

    private static String decisionCn(String decision) {
        return switch (decision) {
            case "ENGAGE" -> "交战";
            case "KITE" -> "游斗/放风筝";
            case "FLEE" -> "撤离";
            case "AVOID" -> "绕行规避";
            case "CORNERED" -> "被围困,需脱困";
            default -> decision;
        };
    }

    private static String fmtSeconds(double v) {
        if (Double.isInfinite(v)) return "∞";
        return round2(v) + "秒";
    }

    /** Gson serialises a non-finite double as the literal {@code Infinity} (invalid JSON) — emit null instead. */
    private static void addNumber(JsonObject root, String key, double v) {
        if (Double.isFinite(v)) {
            root.addProperty(key, round2(v));
        } else {
            root.add(key, JsonNull.INSTANCE);   // +∞ = "nothing can hit me / nothing to clear"
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
