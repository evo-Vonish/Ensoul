package com.dwinovo.numen;

import com.dwinovo.numen.agent.skill.SkillRegistry;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;

import java.nio.file.Path;

public class NumenFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        Path numenConfigRoot = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve(Constants.MOD_ID);
        Path skillsDir = numenConfigRoot.resolve("skills");

        // Skills live under config/numen/skills. Hook the resource reload
        // pipeline so /reload picks up newly added SKILL.md files without a
        // client restart.
        Identifier skillLoaderId = Identifier.fromNamespaceAndPath(Constants.MOD_ID, "skill_loader");
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES)
                .registerReloadListener(new SimpleSynchronousResourceReloadListener() {
                    @Override
                    public Identifier getFabricId() {
                        return skillLoaderId;
                    }

                    @Override
                    public void onResourceManagerReload(ResourceManager rm) {
                        // The engine ships no built-in skills; pick up any SKILL.md the
                        // player (or a tool pack) has placed under config/numen/skills.
                        SkillRegistry.instance().scan(skillsDir);
                    }
                });

        // G → companion roster panel (chat entry + settings/reset live in there).
        KeyMappingHelper.registerKeyMapping(com.dwinovo.numen.client.NumenKeys.OPEN_ROSTER);
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK
                .register(client -> {
                    com.dwinovo.numen.client.NumenKeys.tick();
                    com.dwinovo.numen.client.hud.NumenToasts.tick();
                    com.dwinovo.numen.client.agent.AgentLoopRegistry.tickAll();
                });

        // HUD: advancement-style activity toasts (top-right) when not watching a panel.
        // 26.1 replaced HudRenderCallback with the keyed HudElementRegistry.
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                net.minecraft.resources.Identifier.fromNamespaceAndPath(Constants.MOD_ID, "numen_toasts"),
                (g, delta) -> com.dwinovo.numen.client.hud.NumenToasts.render(g));

        // In-world path overlay for every companion (Baritone PathRenderer port),
        // drawn after translucent terrain so it sits over the world.
        net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES
                .register(ctx -> com.dwinovo.numen.client.path.PathVizRenderer.render(ctx.poseStack()));

        // Drop every path overlay on disconnect so a frozen path can't survive a
        // relog (the server can't send a clear to a player who's already gone).
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT
                .register((handler, client) -> {
                    com.dwinovo.numen.client.path.ClientPathViz.clearAll();
                    com.dwinovo.numen.client.data.ClientNumenInventory.clear();
                    com.dwinovo.numen.client.hud.NumenToasts.clear();
                    com.dwinovo.numen.client.agent.ClientDeaths.clearAll();
                    // W7: wipe this client's control-lease view alongside the rest of the disconnect
                    // teardown — with nothing left to push a replacement, a stale EXTERNAL/held snapshot
                    // must not survive into the next session's companion roster (see ClientControl.clear).
                    com.dwinovo.numen.client.agent.ClientControl.instance().clear();
                });
    }
}
