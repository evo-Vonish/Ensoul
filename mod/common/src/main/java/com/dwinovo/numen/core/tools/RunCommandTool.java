package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.perm.CompanionPermissions;
import com.dwinovo.numen.core.tool.Schema;
import com.dwinovo.numen.core.tool.ServerNumenTool;
import com.dwinovo.numen.entity.Companions;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.ContextChain;
import net.minecraft.commands.CommandResultCallback;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * DERIVATIVE-ADDED TOOL (ours) — not part of upstream numen-core's sample tool
 * pack. Lets the companion run a vanilla-style chat command as itself.
 *
 * <p>Query tool (raw {@link ServerNumenTool}): it runs on the body server-side
 * and replies in place, same tick, no task queue — same pattern as
 * {@code GetSelfStatusTool}.
 *
 * <h2>Policy</h2>
 * <ul>
 *   <li>The command executes AS the companion fake player: the
 *       {@link CommandSourceStack} is built from
 *       {@link ServerPlayer#createCommandSourceStack()}, so {@code @s}, position
 *       and dimension resolve to the body.</li>
 *   <li>Permission is gated by the per-companion OP master switch (G-panel Settings), then capped:
 *       <ul>
 *         <li><b>OP off</b> (default) — floored to {@code all} (level 0): no operator privileges.</li>
 *         <li><b>OP on</b> — {@code min(owner's level, the /numenperm tier)}; the tier defaults to
 *             {@code gamemasters} when unset ({@link CompanionPermissions#tierWhenOpEnabled}). Never
 *             elevates past the owner, never past the configured tier. Offline owner → {@code all}.</li>
 *       </ul>
 *       The OP flag is engine-persisted per companion ({@code Companions.isOpEnabled}); the tier stays
 *       the pack-side {@code /numenperm} dial.</li>
 *   <li>Command feedback (success AND failure lines) is captured through a
 *       collecting {@link CommandSource} attached with
 *       {@link CommandSourceStack#withSource} and returned to the model, so it can
 *       learn Minecraft's rules and self-correct. Admins are NOT informed.</li>
 *   <li><b>Honest settlement (not just "dispatched").</b> In 26.1.2
 *       {@link Commands#performPrefixedCommand} returns {@code void} — the command is
 *       queued and run to completion through an {@code ExecutionContext} whose
 *       terminal result flows to the source's own {@link CommandResultCallback}
 *       ({@code ExecutionCommandSource.resultConsumer()} calls
 *       {@code source.callback().onResult(success, result)}). We attach a callback via
 *       {@link CommandSourceStack#withCallback} and report the <em>real</em> outcome:
 *       executed (with result code / how many times it fired), executed-but-no-effect
 *       (result 0), a runtime failure, a syntax/unknown command, or a permission
 *       rejection — instead of blindly claiming success. This stops the model from
 *       re-issuing a command that never took effect.</li>
 *   <li><b>Permission rejection is detected up front.</b> Operator-gated command nodes
 *       (and entity-selector permissions) are skipped during Brigadier parsing when the
 *       source's level is too low, so a capped command "doesn't exist" and would fail
 *       with a generic parse error indistinguishable from a typo. We instead parse the
 *       command once under the effective cap and, if it does not resolve to a runnable
 *       command, re-parse ascending {@link PermissionLevel}s to find the minimum level
 *       that WOULD run it. If that minimum exceeds the cap, we report a clear
 *       "needs OP level N" rejection so the model asks its owner rather than retrying.
 *       Parsing is side-effect free, so the probe never runs anything.</li>
 *   <li>Dispatched through the server's command dispatcher via
 *       {@code server.getCommands().performPrefixedCommand(stack, command)} — that
 *       already strips a leading "/", and we strip one defensively too.</li>
 * </ul>
 */
public final class RunCommandTool extends ServerNumenTool {

    private static final Gson GSON = new Gson();

    private record Args(String command) {}

    @Override
    public String name() {
        return "run_command";
    }

    @Override
    public String description() {
        return "Run a Minecraft command as yourself, exactly like typing / in chat. Operator-only commands "
                + "work only when your owner has enabled OP for you (G-panel Settings) AND their own level "
                + "allows it; with OP off you can still run non-operator commands. "
                + "The result reports what ACTUALLY happened: whether the command executed (with its result "
                + "code), executed but changed nothing, failed at runtime, was a syntax error, or was rejected "
                + "for lack of OP permission. If it says the command needs OP and yours is off, do NOT retry it "
                + "— ask your owner to enable OP for you, or use a way that needs no permission. "
                + "The command's own output lines are also returned in \"feedback\" so you can read what "
                + "happened and fix mistakes. Give the command with or without a leading slash. "
                + "Examples: \"time set day\", \"tp @s ~ ~10 ~\".";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("command", "The command to run, with or without a leading '/'. "
                        + "E.g. \"time set day\" or \"tp @s ~ ~10 ~\".")
                .build();
    }

    @Override
    public void runOnServer(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        try {
            Args a = GSON.fromJson(args, Args.class);
            String command = a.command() == null ? "" : a.command().strip();
            if (command.startsWith("/")) {
                command = command.substring(1).strip();
            }
            if (command.isEmpty()) {
                reply.accept(fail("没有提供任何命令。"));
                return;
            }

            MinecraftServer server = self.level().getServer();
            if (server == null) {
                reply.accept(fail("当前没有可用的服务器来执行命令。"));
                return;
            }

            // Collect every system message the command emits (success + failure), so the model
            // sees the outcome verbatim and learns the rules. Do not inform admins.
            List<String> feedback = new ArrayList<>();
            CommandSource collector = new CommandSource() {
                @Override
                public void sendSystemMessage(Component message) {
                    feedback.add(message.getString());
                }

                @Override
                public boolean acceptsSuccess() {
                    return true;
                }

                @Override
                public boolean acceptsFailure() {
                    return true;
                }

                @Override
                public boolean shouldInformAdmins() {
                    return false;
                }
            };

            // Execute AS the companion, with feedback routed to our collector.
            CommandSourceStack base = self.createCommandSourceStack().withSource(collector);

            // Effective command permission (the OP master switch is the gate; /numenperm is the ceiling dial):
            //   OP off -> floored to ALL (level 0): no operator privileges, whatever the owner or tier is.
            //   OP on  -> min(owner's level, the /numenperm tier — default gamemasters if unset): never past
            //             the owner, never past the configured tier. Offline owner -> ALL (never elevate).
            boolean opEnabled = Companions.isOpEnabled(server, self.getUUID());
            ServerPlayer owner = self.resolveOwnerPlayer();
            PermissionLevel effective;
            if (!opEnabled) {
                effective = PermissionLevel.ALL;
            } else {
                PermissionLevel ownerLevel = owner != null
                        ? server.getProfilePermissions(owner.nameAndId()).level()
                        : PermissionLevel.ALL;
                PermissionLevel tier = CompanionPermissions.tierWhenOpEnabled(self.getName().getString());
                effective = ownerLevel.id() <= tier.id() ? ownerLevel : tier;   // min(owner, tier)
            }
            CommandSourceStack effStack = base.withPermission(LevelBasedPermissionSet.forLevel(effective));

            CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();

            // --- Permission / syntax preflight (parse-only, no side effects) ---------------------
            // A command whose node the source may not use is SKIPPED during parsing, so under the cap
            // it looks like an unknown command — indistinguishable from a typo. Decide which it is by
            // re-parsing under higher levels: if some higher level WOULD run it, the block is a
            // permission cap, not a syntax error.
            if (!parsesToExecutable(dispatcher, effStack, command)) {
                PermissionLevel required = minExecutableLevel(dispatcher, base, command);
                if (required != null && required.id() > effective.id()) {
                    // Permission rejection — the core of the "fake success" bug. Tell the model to STOP
                    // and ask for OP instead of retrying a command the cap will never let through.
                    String why = opEnabled
                            ? "但该同伴当前授予的权限等级只有 " + effective.id() + "(" + effective.getSerializedName()
                              + "),不足。请主人在设置面板提升它的权限档,或改用不需要该权限的方式。"
                            : "但该同伴 OP 未开启(当前等级 0/all),已拒绝。请主人在设置面板为它开启 OP,或改用不需要权限的方式。";
                    reply.accept(result(false,
                            "此命令需要 OP 权限(等级 " + required.id() + "/" + required.getSerializedName() + ")," + why
                                    + " 不要重复重试同一命令。",
                            feedback));
                    return;
                }
                // Genuinely unknown/malformed at every level. Run it under the cap so Minecraft emits its
                // own error text into feedback, then report an honest syntax failure.
                server.getCommands().performPrefixedCommand(effStack, command);
                reply.accept(result(false,
                        "命令语法错误或未知:/" + command + "。请查看 feedback 里的报错并修正,或改用其它命令。",
                        feedback));
                return;
            }

            // --- Parsable under the cap: execute and settle on the REAL result -------------------
            // performPrefixedCommand returns void in 26.1.2; the terminal result is delivered to the
            // source's own callback. Attach one and read what actually happened.
            int[] successCount = {0};
            int[] resultSum = {0};
            int[] failureCount = {0};
            CommandResultCallback callback = (ok, resultCode) -> {
                if (ok) {
                    successCount[0]++;
                    resultSum[0] += resultCode;
                } else {
                    failureCount[0]++;
                }
            };
            CommandSourceStack runStack = effStack.withCallback(callback);

            // Dispatcher already trims an optional leading "/"; we stripped one above too.
            server.getCommands().performPrefixedCommand(runStack, command);

            String message;
            boolean success;
            if (successCount[0] > 0 && resultSum[0] > 0) {
                success = true;
                message = "已执行:/" + command + "(结果码 result=" + resultSum[0]
                        + (successCount[0] > 1 ? ",命令成功触发 " + successCount[0] + " 次" : "")
                        + ")。详情见 feedback。";
            } else if (successCount[0] > 0) {
                // Ran and reported success, but result code 0 — nothing measurable changed.
                success = true;
                message = "已执行:/" + command + ",但结果码为 0——目标可能已处于该状态、条件不成立或无可见效果,"
                        + "无需重试。详情见 feedback。";
            } else {
                // The command parsed but did not report any success: it failed at runtime (e.g. no matching
                // target, condition unmet, invalid argument). Its own error line is in feedback.
                success = false;
                message = "命令未能成功生效:/" + command + "。可能是目标不存在、条件不满足或参数无效——请看 feedback 的报错,"
                        + "调整后再试,不要原样重复。";
            }
            reply.accept(result(success, message, feedback));
        } catch (Exception e) {
            // A command must never crash the server tick — surface it as a failure reply.
            String msg = e.getMessage();
            reply.accept(fail("命令执行时抛出异常:" + (msg == null ? e.getClass().getSimpleName() : msg)));
        }
    }

    /**
     * True iff {@code command} parses under {@code src} all the way to a runnable command. Mirrors
     * {@code Commands.finishParsing}: a leftover/parse exception, or a chain that doesn't flatten to an
     * executable node (e.g. a permission-gated literal was skipped, or the command is incomplete), is not
     * executable. Pure parse — no command runs, no feedback is emitted.
     */
    private static boolean parsesToExecutable(CommandDispatcher<CommandSourceStack> dispatcher,
                                              CommandSourceStack src, String command) {
        ParseResults<CommandSourceStack> parse = dispatcher.parse(command, src);
        if (Commands.getParseException(parse) != null) {
            return false;
        }
        return ContextChain.tryFlatten(parse.getContext().build(command)).isPresent();
    }

    /**
     * The lowest {@link PermissionLevel} (ascending ALL→OWNERS) under which {@code command} resolves to a
     * runnable command, or {@code null} if no level up to OWNERS can run it (genuinely unknown/malformed).
     * Used only to tell a permission cap apart from a real syntax error.
     */
    private static PermissionLevel minExecutableLevel(CommandDispatcher<CommandSourceStack> dispatcher,
                                                      CommandSourceStack base, String command) {
        for (PermissionLevel level : PermissionLevel.values()) {   // ALL(0), MODERATORS(1) ... OWNERS(4)
            CommandSourceStack probe = base.withPermission(LevelBasedPermissionSet.forLevel(level));
            if (parsesToExecutable(dispatcher, probe, command)) {
                return level;
            }
        }
        return null;
    }

    /** Build the standard {@code {success, message, feedback[]}} reply. */
    private static String result(boolean success, String message, List<String> feedback) {
        JsonObject root = new JsonObject();
        root.addProperty("success", success);
        root.addProperty("message", message);
        JsonArray fb = new JsonArray();
        for (String line : feedback) {
            fb.add(line);
        }
        root.add("feedback", fb);
        return root.toString();
    }

    /** Feedback-less failure reply, for guards that fire before any command runs. */
    private static String fail(String message) {
        JsonObject root = new JsonObject();
        root.addProperty("success", false);
        root.addProperty("message", message);
        return root.toString();
    }
}
