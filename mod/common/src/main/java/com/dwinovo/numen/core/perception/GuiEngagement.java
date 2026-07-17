package com.dwinovo.numen.core.perception;

import com.dwinovo.numen.core.pathing.exec.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * "工位姿态" — the GUI-engagement posture. When a companion has a container menu open
 * (a workbench / furnace / chest, or a villager trade), it should behave like a real
 * player standing at that station: <em>face the thing</em> and <em>stay planted</em>
 * for the whole session — including the idle stretches while the LLM is thinking
 * between tool calls. Without this, the attention brain reclaims the head a few ticks
 * after the opening click (the {@code interact_at} hard-aim expires) and the companion
 * swivels off to watch a passing sheep, or the idle-autonomy layer turns its body and
 * strolls, all with the menu still open.
 *
 * <h2>How it works</h2>
 * <ul>
 *   <li><b>Registration.</b> The interaction primitive ({@link
 *       com.dwinovo.numen.core.pathing.exec.Interaction}) calls {@link #registerIfMenuOpen}
 *       right after a right-click that actually opened a block/entity menu, recording the
 *       {@link BlockPos} or entity id to face. (A lever/button/door click opens no menu, so
 *       nothing is registered.)</li>
 *   <li><b>Per tick.</b> {@link #tick} runs once per live companion from the dispatcher. While the
 *       body's {@code containerMenu} is NOT its own always-present {@code inventoryMenu} <em>and</em>
 *       a target is registered, it feeds the look engine {@code markEngaged(point)} (the engine
 *       squares the body up and holds the head on it) and {@link InputDriver#halt}s locomotion so
 *       the body can't wander. The moment the menu closes ({@code containerMenu == inventoryMenu}),
 *       the registration is dropped and attention returns naturally.</li>
 * </ul>
 *
 * <h2>Yielding</h2>
 * The engine's priority ladder already puts a hard-aim (combat / reflex fight-back) above engaged,
 * so a reflex that snaps the head wins the gaze. For the BODY, this pass runs from the dispatcher —
 * which ticks BEFORE the reflex layer ({@code Perceptions::tick}) each server tick — so a survival
 * reflex's movement inputs are written last and win by ordering. As an explicit belt-and-braces
 * guard we also stand down entirely while {@link Reflexes#ownsBody} reports a movement-owning
 * episode (flee / turtle / creeper sprint), keeping the registration so engagement resumes if the
 * menu is still open once the episode ends.
 *
 * <p>Server-thread only (constructed/read from the end-of-server-tick dispatcher), like the rest of
 * the perception layer; the per-companion map needs no synchronisation.
 */
public final class GuiEngagement {

    private GuiEngagement() {}

    /** A registered thing to face while its menu is open: a block station, or an entity (trade). */
    private static final class Target {
        final BlockPos block;   // non-null → block station GUI
        final int entityId;     // >= 0 → entity GUI (villager trade); -1 when block-based

        private Target(BlockPos block, int entityId) {
            this.block = block;
            this.entityId = entityId;
        }
    }

    /** Per-companion registered GUI target, keyed by UUID. Server-thread only. */
    private static final Map<UUID, Target> TARGETS = new HashMap<>();

    /**
     * Register the block just right-clicked as the engagement target — but only if that click
     * actually opened a menu (so a lever/button/door, which consumes the click but opens nothing,
     * registers nothing). Called from the interaction primitive right after a successful use.
     */
    public static void registerIfMenuOpen(NumenPlayer body, BlockPos pos) {
        if (menuOpen(body)) {
            TARGETS.put(body.getUUID(), new Target(pos.immutable(), -1));
        }
    }

    /** Entity overload: register the entity just interacted with (a villager whose trade GUI opened). */
    public static void registerIfMenuOpen(NumenPlayer body, Entity entity) {
        if (menuOpen(body)) {
            TARGETS.put(body.getUUID(), new Target(null, entity.getId()));
        }
    }

    /** Drop a companion's registration when its body leaves the world (mirrors LookBrain.forget). */
    public static void forget(UUID id) {
        TARGETS.remove(id);
    }

    /** One per-companion engagement tick. Cheap when nothing is registered / no menu is open. */
    public static void tick(NumenPlayer body) {
        UUID id = body.getUUID();

        // Reflex 让位: while a survival reflex drives the body, stand down — don't fight it for the
        // head or the movement inputs. Keep the registration so engagement resumes if the menu is
        // still open once the episode ends. (Same body-ownership gate the task loop uses.)
        if (Reflexes.ownsBody(body)) {
            return;
        }

        if (!menuOpen(body)) {
            // The block/entity menu closed (close_gui, or it never opened) — attention returns.
            TARGETS.remove(id);
            return;
        }

        Target t = TARGETS.get(id);
        if (t == null) {
            return;   // a menu we didn't register (nothing to face) — leave the look engine alone
        }

        Vec3 point = resolve(body, t);
        if (point == null) {
            TARGETS.remove(id);   // target gone (block mined / entity despawned) — let attention resume
            return;
        }

        // Face it and hold it, for the whole session (across the LLM's thinking gaps): refresh the
        // engaged latch and zero locomotion so the planted body can only turn, never stroll.
        body.getLook().markEngaged(point.x, point.y, point.z);
        InputDriver.halt(body);
    }

    /**
     * A block/entity GUI is open exactly when {@code containerMenu} is NOT the always-present
     * own-inventory menu (the 2x2-crafting inventory carries no engagement). Matches the same test
     * the GUI tools use to distinguish a station menu from the bare inventory.
     */
    private static boolean menuOpen(NumenPlayer body) {
        return body.containerMenu != body.inventoryMenu;
    }

    /** The live world point to face: a block centre, or the entity's current eye position. */
    private static Vec3 resolve(NumenPlayer body, Target t) {
        if (t.block != null) {
            return Vec3.atCenterOf(t.block);
        }
        Entity e = body.level().getEntity(t.entityId);
        if (e == null || !e.isAlive()) {
            return null;
        }
        return e.getEyePosition();
    }
}
