package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.perm.CompanionPermissions;
import com.dwinovo.numen.core.tool.Schema;
import com.dwinovo.numen.core.tool.ServerNumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
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
 *   <li>Permission is capped at the OWNER's level, never more: if the owner is
 *       online we set the stack's permission to whatever the server grants the
 *       owner ({@code getProfilePermissions(owner.nameAndId())}). If the owner is
 *       offline/unresolvable we leave the companion's own default permission
 *       untouched — we do NOT elevate.</li>
 *   <li>Command feedback (success AND failure lines) is captured through a
 *       collecting {@link CommandSource} attached with
 *       {@link CommandSourceStack#withSource} and returned to the model, so it can
 *       learn Minecraft's rules and self-correct. Admins are NOT informed.</li>
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
        return "Run a Minecraft command as yourself, exactly like typing / in chat. Your permission "
                + "is capped at your OWNER's level, so operator-only commands work only if they can. "
                + "The command's own output lines are returned in \"feedback\" so you can read what "
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
                reply.accept(fail("No command was provided."));
                return;
            }

            MinecraftServer server = self.level().getServer();
            if (server == null) {
                reply.accept(fail("No server available to run the command."));
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
            CommandSourceStack stack = self.createCommandSourceStack().withSource(collector);

            // Cap at the owner's permission level (never elevate beyond it). Offline owner ->
            // keep the companion's own default permission, i.e. no elevation.
            ServerPlayer owner = self.resolveOwnerPlayer();
            if (owner != null) {
                stack = stack.withPermission(server.getProfilePermissions(owner.nameAndId()));
            }

            // Dispatcher already trims an optional leading "/"; we stripped one above too.
            server.getCommands().performPrefixedCommand(stack, command);

            JsonObject root = new JsonObject();
            root.addProperty("success", true);
            root.addProperty("message", "Dispatched /" + command + " — see \"feedback\" for the server's response.");
            JsonArray fb = new JsonArray();
            for (String line : feedback) {
                fb.add(line);
            }
            root.add("feedback", fb);
            reply.accept(root.toString());
        } catch (Exception e) {
            // A command must never crash the server tick — surface it as a failure reply.
            String msg = e.getMessage();
            reply.accept(fail("Command failed: " + (msg == null ? e.getClass().getSimpleName() : msg)));
        }
    }

    private static String fail(String message) {
        JsonObject root = new JsonObject();
        root.addProperty("success", false);
        root.addProperty("message", message);
        return root.toString();
    }
}
