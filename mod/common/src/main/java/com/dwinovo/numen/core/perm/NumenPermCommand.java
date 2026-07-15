package com.dwinovo.numen.core.perm;

import com.dwinovo.numen.entity.NumenPlayer;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The server-side {@code /numenperm} command tree — the owner-facing management
 * surface for {@link CompanionPermissions}. Modelled on the engine's
 * {@code /numen} tree ({@code com.dwinovo.numen.entity.NumenCommands}): built
 * with brigadier via {@link Commands#literal}/{@link Commands#argument}, runs
 * entirely on the server, and carries <em>no</em> {@code .requires(...)} gate so
 * any player may invoke it — the owner check lives inside the {@code set} verb.
 *
 * <pre>
 *   /numenperm &lt;companion&gt; &lt;tier&gt;   grant/revoke a tier (owner-only) — tier ∈ all|moderators|gamemasters|admins|owners
 *   /numenperm &lt;companion&gt;           show a companion's current tier
 *   /numenperm list                      show every stored (companion → tier) entry
 * </pre>
 *
 * <p>Registered per loader — see {@code NumenCoreFabric} (Fabric
 * {@code CommandRegistrationCallback}) and {@code NumenCoreNeoForge}
 * (NeoForge {@code RegisterCommandsEvent}).
 */
public final class NumenPermCommand {

    private NumenPermCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("numenperm")
                // Literal children are matched before the <companion> argument, so `list` is
                // reserved (a companion literally named "list" can't be queried this way).
                .then(Commands.literal("list")
                        .executes(NumenPermCommand::listAll))
                .then(Commands.argument("companion", StringArgumentType.word())
                        .suggests(NumenPermCommand::suggestCompanions)
                        // /numenperm <companion> — query.
                        .executes(ctx -> query(ctx, StringArgumentType.getString(ctx, "companion")))
                        .then(Commands.argument("tier", StringArgumentType.word())
                                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                        CompanionPermissions.tierNames(), b))
                                // /numenperm <companion> <tier> — set (owner-only).
                                .executes(ctx -> set(ctx,
                                        StringArgumentType.getString(ctx, "companion"),
                                        StringArgumentType.getString(ctx, "tier"))))));
    }

    private static int set(CommandContext<CommandSourceStack> ctx, String companion, String tierArg) {
        CommandSourceStack src = ctx.getSource();

        PermissionLevel tier = CompanionPermissions.tierByName(tierArg);
        if (tier == null) {
            src.sendFailure(Component.literal("Unknown tier '" + tierArg + "'. Use one of: "
                    + String.join(", ", CompanionPermissions.tierNames())));
            return 0;
        }

        ServerPlayer executor = src.getPlayer();
        if (executor == null) {
            src.sendFailure(Component.literal("Only a player can set a companion's permission tier."));
            return 0;
        }

        // Owner gate: if the companion is live, only its owner may change its tier. If it isn't
        // currently in the world we can't resolve ownership (the store is name-keyed and survives
        // respawns) — allow the set and note that it takes effect on the companion's next command.
        NumenPlayer live = findLiveCompanion(src.getServer(), companion);
        boolean notInWorld = live == null;
        if (live != null && !live.isOwnedByPlayer(executor.getUUID())) {
            src.sendFailure(Component.literal("Companion '" + live.getName().getString()
                    + "' isn't yours — only its owner can change its tier."));
            return 0;
        }

        CompanionPermissions.set(companion, tier);
        String note = notInWorld ? " (companion not currently in world — applies on next command)" : "";
        src.sendSuccess(() -> Component.literal("Set companion '" + companion + "' permission tier to '"
                + tier.getSerializedName() + "'." + note), false);
        return 1;
    }

    private static int query(CommandContext<CommandSourceStack> ctx, String companion) {
        PermissionLevel lvl = CompanionPermissions.get(companion);
        ctx.getSource().sendSuccess(() -> Component.literal("Companion '" + companion
                + "' permission tier: " + lvl.getSerializedName()), false);
        return 1;
    }

    private static int listAll(CommandContext<CommandSourceStack> ctx) {
        Map<String, PermissionLevel> all = CompanionPermissions.all();
        if (all.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "No companion permission tiers set — all default to '"
                            + CompanionPermissions.DEFAULT_LEVEL.getSerializedName() + "'."), false);
            return 1;
        }
        StringBuilder sb = new StringBuilder("Companion permission tiers:");
        for (Map.Entry<String, PermissionLevel> e : all.entrySet()) {
            sb.append("\n  ").append(e.getKey()).append(" -> ").append(e.getValue().getSerializedName());
        }
        String msg = sb.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return all.size();
    }

    /** First live companion whose name matches {@code name} (case-insensitive), or {@code null}. */
    private static NumenPlayer findLiveCompanion(MinecraftServer server, String name) {
        if (server == null || name == null) return null;
        String want = name.strip();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p instanceof NumenPlayer np && np.getName().getString().equalsIgnoreCase(want)) {
                return np;
            }
        }
        return null;
    }

    /** Suggest live companion names — the executor's own if a player, else all (console). */
    private static CompletableFuture<Suggestions> suggestCompanions(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        MinecraftServer server = ctx.getSource().getServer();
        List<String> names = new ArrayList<>();
        if (server != null) {
            ServerPlayer executor = ctx.getSource().getPlayer();
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (p instanceof NumenPlayer np
                        && (executor == null || np.isOwnedByPlayer(executor.getUUID()))) {
                    names.add(np.getName().getString());
                }
            }
        }
        return SharedSuggestionProvider.suggest(names, builder);
    }
}
