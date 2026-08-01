package com.dwinovo.numen.client.screen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.List;

/**
 * Pure, Minecraft-free formatting helpers for the chat transcript's tool rows.
 * Factored out of {@link NumenScreen} so the human-facing "what is the companion
 * doing" logic — reading the model-written {@code d} narration and stitching the
 * folded-group summary — can be asserted in isolation (it depends only on Gson).
 *
 * <p>The chat panel no longer prints raw {@code <...>} tags or JSON tool args in
 * its collapsed view; it shows the model's own one-line narration ({@code d}) and
 * falls back to the bare tool name when a call didn't write one.
 */
public final class ToolLine {

    private ToolLine() {}

    /**
     * The model-written {@code description} narration from a tool call's raw args JSON,
     * or {@code null} when it is absent, blank, or the JSON doesn't parse (e.g. a
     * still-streaming partial call). Never throws.
     */
    public static String narration(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) return null;
        try {
            JsonObject o = JsonParser.parseString(argsJson).getAsJsonObject();
            if (o.has("description") && o.get("description").isJsonPrimitive()) {
                String d = o.get("description").getAsString().trim();
                return d.isEmpty() ? null : d;
            }
        } catch (RuntimeException ignored) {
            // partial / malformed args while streaming — fall back to the tool name
        }
        return null;
    }

    /**
     * Collapsed one-line label for a single tool call: its {@code d} narration when
     * the model wrote one, else the bare tool name (no args JSON). Never null.
     */
    public static String label(String toolName, String argsJson) {
        String d = narration(argsJson);
        if (d != null) return d;
        return toolName == null ? "" : toolName;
    }

    /**
     * Folded-group summary line, e.g. {@code "▸ 15 步：挖铁矿补装备、回家存货"} — the
     * per-call labels (each a {@code d} narration or a tool name) joined with a
     * Chinese enumeration comma. The caller ellipsizes it to the row width.
     */
    public static String groupSummary(int size, List<String> labels) {
        return "▸ " + size + " 步：" + String.join("、", labels);
    }

    /**
     * Collapsed step-digest body — the chat-panel v2 aggregation of everything between two
     * adjacent spoken messages, e.g. {@code "▸ 8 步 · 挖铁矿补装备、回工作台合成"}: the tool-step
     * count plus each step's label (a {@code d} narration or bare tool name) joined with a
     * Chinese enumeration comma. The caller width-ellipsizes this and appends {@link
     * #digestSuffix} pinned to the row's right edge.
     *
     * <p>When the interval carried no tool steps (only sightings / intermediate thinking) it
     * degrades to a bare {@code "▸ 过程"} so the row still reads as a foldable process summary.
     */
    public static String digestBody(int steps, List<String> labels) {
        if (steps <= 0) return "▸ 过程";
        String head = "▸ " + steps + " 步";
        if (labels == null || labels.isEmpty()) return head;
        return head + " · " + String.join("、", labels);
    }

    /**
     * The {@code " · 含见闻/思考"} tail appended to a collapsed digest when the interval also
     * carried system sightings (cognition notes) and/or intermediate ("middle-turn") thinking.
     * Empty when it carried neither. Kept separate from {@link #digestBody} so the caller can
     * pin it to the row's right edge, past the width-ellipsized body.
     */
    public static String digestSuffix(boolean hasEvents, boolean hasThink) {
        if (hasEvents && hasThink) return " · 含见闻·思考";
        if (hasEvents) return " · 含见闻";
        if (hasThink) return " · 含思考";
        return "";
    }
}
