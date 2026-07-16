package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.llm.NumenLlmClient;
import com.dwinovo.numen.agent.provider.AssistantTurn;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.platform.Services;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Full-context export for human review ("一起审阅问题"): dumps one companion's
 * entire working context to {@code <gameDir>/config/numen/exports/} as
 * <ul>
 *   <li>{@code <name>_<stamp>.md} — human-readable: header (identity, backend,
 *       session usage), the exact system prompt, the tool list, and every
 *       conversation message in order — content verbatim inside code fences
 *       (fidelity beats markdown prettiness), per-turn reasoning where the
 *       panel captured it, attachments noted;</li>
 *   <li>{@code <name>_<stamp>_wire.json} — the LAST raw request body actually
 *       sent for this companion, byte-for-byte as captured right before
 *       dispatch ({@link NumenLlmClient#lastWire}).</li>
 * </ul>
 * Pure diagnostics — reads live state, writes files, mutates nothing.
 */
public final class ContextExporter {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Pattern BACKTICK_RUNS = Pattern.compile("`+");

    private ContextExporter() {}

    /**
     * Write both export files for {@code loop}'s companion. Returns the markdown path
     * (the wire dump sits alongside it). Throws {@link IOException} upward — the UI
     * trigger wraps everything.
     */
    public static Path export(EntityAgentLoop loop) throws IOException {
        UUID uuid = loop.entityUuid();
        String name = rosterName(uuid);
        Path dir = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve("numen").resolve("exports");
        Files.createDirectories(dir);
        String base = sanitize(name) + "_" + STAMP.format(LocalDateTime.now());
        Path md = dir.resolve(base + ".md");
        Path wire = dir.resolve(base + "_wire.json");

        Files.writeString(md, buildMarkdown(loop, name), StandardCharsets.UTF_8);
        NumenLlmClient.WireCapture cap = NumenLlmClient.lastWire(uuid);
        Files.writeString(wire, cap != null ? cap.body()
                        : "{\"note\":\"no request captured for this companion yet this session\"}",
                StandardCharsets.UTF_8);
        Constants.LOG.info("[numen-export] wrote {}", md.toAbsolutePath());
        Constants.LOG.info("[numen-export] wrote {}", wire.toAbsolutePath());
        return md;
    }

    private static String buildMarkdown(EntityAgentLoop loop, String name) {
        UUID uuid = loop.entityUuid();
        List<ConvoState.Msg> snap = loop.convo().snapshot();
        var cfg = Services.CONFIG;
        NumenLlmClient.WireCapture cap = NumenLlmClient.lastWire(uuid);
        UsageTracker.Stat mine = UsageTracker.instance().perCompanion().get(uuid);
        UsageTracker.Stat all = UsageTracker.instance().global();

        StringBuilder sb = new StringBuilder(1 << 16);
        sb.append("# Numen context export — ").append(name).append("\n\n");
        sb.append("- companion: ").append(name).append(" (`").append(uuid).append("`)\n");
        sb.append("- provider/model: ").append(cfg.getProvider()).append(" / ").append(cfg.getModel()).append('\n');
        sb.append("- reasoningEffort: ").append(cfg.getReasoningEffort()).append('\n');
        sb.append("- exported: ").append(LocalDateTime.now()).append('\n');
        sb.append("- prefixHash (last request): ")
                .append(cap != null ? cap.prefixHash() : "n/a — no request captured yet").append('\n');
        // (helper below: ", cache NN%" or "" when the provider reported no cache detail)
        sb.append("- session usage (this companion): ").append(mine != null
                ? mine.requests() + " req, prompt " + mine.promptTokens()
                        + " tok, completion " + mine.completionTokens() + " tok" + cacheNote(mine)
                : "none").append('\n');
        sb.append("- session usage (global): ").append(all.requests()).append(" req, prompt ")
                .append(all.promptTokens()).append(" tok, completion ")
                .append(all.completionTokens()).append(" tok").append(cacheNote(all)).append('\n');
        sb.append("- messages: ").append(snap.size()).append('\n');

        sb.append("\n## System prompt\n\n");
        fenced(sb, loop.currentSystemPrompt());

        List<NumenTool> tools = ToolRegistry.all();
        sb.append("\n## Tools (").append(tools.size()).append(")\n\n");
        for (NumenTool t : tools) {
            sb.append("- **").append(t.name()).append("** — ").append(firstLine(t.description())).append('\n');
        }

        sb.append("\n## Conversation\n");
        for (int i = 0; i < snap.size(); i++) {
            switch (snap.get(i)) {
                case ConvoState.Msg.User u -> {
                    sb.append("\n### [").append(i).append("] user\n\n");
                    if (!u.attachments().isEmpty()) {
                        sb.append("*attachments: ").append(String.join(", ", u.attachments())).append("*\n\n");
                    }
                    fenced(sb, u.content());
                }
                case ConvoState.Msg.Assistant a -> {
                    sb.append("\n### [").append(i).append("] assistant\n\n");
                    String reasoning = loop.reasoningAt(i);
                    if (reasoning != null && !reasoning.isBlank()) {
                        sb.append("<details><summary>reasoning</summary>\n\n");
                        fenced(sb, reasoning);
                        sb.append("\n</details>\n\n");
                    }
                    AssistantTurn turn = a.turn();
                    if (turn.content() != null && !turn.content().isBlank()) {
                        fenced(sb, turn.content());
                    }
                    for (LlmToolCall tc : turn.toolCalls()) {
                        sb.append("\n**tool_call** `").append(tc.name()).append("` (id ")
                                .append(tc.id()).append(")\n\n");
                        fenced(sb, tc.arguments());
                    }
                    if (!turn.hasToolCalls() && (turn.content() == null || turn.content().isBlank())) {
                        sb.append("*(empty turn)*\n");
                    }
                }
                case ConvoState.Msg.Tool t -> {
                    sb.append("\n### [").append(i).append("] tool result (id ").append(t.toolCallId()).append(")\n\n");
                    fenced(sb, t.content());
                }
            }
        }
        return sb.toString();
    }

    /**
     * Verbatim body inside a code fence one backtick longer than the longest backtick run
     * in the body — embedded fences can't break out, nothing gets escaped or altered.
     */
    private static void fenced(StringBuilder sb, String body) {
        String s = body == null ? "" : body;
        int longest = 3;
        Matcher m = BACKTICK_RUNS.matcher(s);
        while (m.find()) longest = Math.max(longest, m.group().length());
        String fence = "`".repeat(longest + 1);
        sb.append(fence).append('\n').append(s).append('\n').append(fence).append('\n');
    }

    /**
     * ", cache NN%" for a usage stat, or "" when the provider reported no cache
     * detail (unknown is not 0%). Provider-metered — the supplier-side audit of
     * the frozen-prefix constitution, alongside the prefixHash line above it.
     */
    private static String cacheNote(UsageTracker.Stat s) {
        double rate = s.cacheHitRate();
        return rate < 0 ? "" : ", cache " + Math.round(rate * 100) + "%";
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        int nl = s.indexOf('\n');
        String line = (nl >= 0 ? s.substring(0, nl) : s).strip();
        return line.length() > 160 ? line.substring(0, 160) + "…" : line;
    }

    private static String rosterName(UUID uuid) {
        for (NumenRoster.Entry e : NumenRoster.instance().entries()) {
            if (e.uuid().equals(uuid)) return e.name();
        }
        return "numen-" + uuid.toString().substring(0, 8);
    }

    /** Strip characters Windows/NTFS rejects in file names; collapse whitespace to '_'. */
    private static String sanitize(String s) {
        String out = s.replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", "_");
        return out.isBlank() ? "numen" : out;
    }
}
