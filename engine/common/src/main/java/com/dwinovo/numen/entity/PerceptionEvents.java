package com.dwinovo.numen.entity;

import com.dwinovo.numen.Constants;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The seam between the engine (which owns the companion body and sees every raw
 * world event that lands on it) and tool packs (which own what those events
 * <em>mean</em>). The engine fires one event per raw occurrence as the body is
 * hurt, gains or loses an effect, or picks something up; a pack subscribes to
 * decide whether that occurrence is worth telling the companion's brain about.
 *
 * <p>This class is <strong>pure seam</strong>: it carries no thresholds, no
 * cooldowns, no batching, no wording, no "is this interesting" judgement. It is
 * a mechanical relay of {@link NumenPlayer} overrides (see {@code hurtServer},
 * {@code onEffectAdded}/{@code onEffectsRemoved}, {@code onItemPickup} on the
 * body) to whoever is listening. <em>All</em> of that policy — rate-limiting a
 * flurry of hits into one "I'm under attack" note, ignoring a one-heart scratch,
 * phrasing an event as a Chinese {@code <event>} for {@link Companions#emitEvent}
 * — lives in the tool pack. Keeping it here would bake a fixed perception policy
 * into the engine; keeping it in the pack lets each pack perceive differently.
 *
 * <p>Every event fires <strong>server-side</strong>, on the tick thread that
 * mutated the body, after the vanilla behaviour has run (so the body's state —
 * remaining health, active effects, inventory — already reflects the event).
 * Each listener is invoked inside its own {@code try}/{@code catch}: a pack
 * listener that throws is logged and skipped, never allowed to abort the server
 * tick or starve the listeners registered after it. Listeners are held in a
 * {@link CopyOnWriteArrayList} so registration is safe from any thread and firing
 * never trips over concurrent registration.
 *
 * <h2>Spam guards (engine responsibility, not policy)</h2>
 * The engine only suppresses <em>non-events</em> — raw occurrences that carry no
 * new information — so a pack never has to filter noise the engine could see was
 * empty:
 * <ul>
 *   <li>{@link #fireHurt} is only fired for hits that actually landed (positive
 *       damage applied, or measurable health lost); immune / cancelled / zero
 *       hits are dropped.</li>
 *   <li>{@link #fireEffectChange} with {@code added == true} fires once when an
 *       effect is first applied and again only when its amplifier changes — the
 *       body's per-tick effect re-syncs (duration ticking down, periodic refreshes)
 *       are dropped by the {@code onEffectUpdated} override before they reach here.</li>
 * </ul>
 * Deciding whether the surviving events matter is still entirely the pack's call.
 */
public final class PerceptionEvents {

    /** Notified when a hit actually lands on a companion body. */
    @FunctionalInterface
    public interface HurtListener {
        void onHurt(HurtInfo info);
    }

    /** Notified when a companion body gains, strengthens, or loses an effect. */
    @FunctionalInterface
    public interface EffectListener {
        void onEffectChange(EffectInfo info);
    }

    /** Notified when a companion body picks an item stack up off the ground. */
    @FunctionalInterface
    public interface PickupListener {
        void onItemPickup(PickupInfo info);
    }

    /**
     * A hit that landed on {@code body}. {@code amount} is the raw incoming
     * damage the game asked for; {@code remainingHealth} is the body's health
     * <em>after</em> the hit resolved (0 for a lethal blow); {@code blocking} is
     * whether the body was actively blocking (shield up) at the instant the hit
     * arrived. {@code source} is the vanilla damage source (attacker / cause).
     */
    public record HurtInfo(NumenPlayer body, DamageSource source, float amount,
                           float remainingHealth, boolean blocking) {}

    /**
     * An effect change on {@code body}. {@code added == true} for a freshly
     * applied effect or an amplifier increase (the {@code effect} is the now-active
     * instance); {@code added == false} for a removed / expired effect (the
     * {@code effect} is the instance that just left).
     */
    public record EffectInfo(NumenPlayer body, MobEffectInstance effect, boolean added) {}

    /**
     * An item {@code body} just picked up. {@code stack} is an immutable copy of
     * what was collected (safe to hold past the tick); {@code count} is how many
     * items that copy represents at pickup time.
     */
    public record PickupInfo(NumenPlayer body, ItemStack stack, int count) {}

    private static final List<HurtListener> ON_HURT = new CopyOnWriteArrayList<>();
    private static final List<EffectListener> ON_EFFECT_CHANGE = new CopyOnWriteArrayList<>();
    private static final List<PickupListener> ON_ITEM_PICKUP = new CopyOnWriteArrayList<>();

    private PerceptionEvents() {}

    /** A hit landed on a companion body (immune / cancelled / zero hits are not delivered). */
    public static void onHurt(HurtListener listener) { ON_HURT.add(listener); }

    /** A companion body gained, strengthened, or lost an effect (per-tick re-syncs are not delivered). */
    public static void onEffectChange(EffectListener listener) { ON_EFFECT_CHANGE.add(listener); }

    /** A companion body picked an item stack up off the ground. */
    public static void onItemPickup(PickupListener listener) { ON_ITEM_PICKUP.add(listener); }

    public static void fireHurt(NumenPlayer body, DamageSource source, float amount,
                                float remainingHealth, boolean blocking) {
        HurtInfo info = new HurtInfo(body, source, amount, remainingHealth, blocking);
        for (HurtListener l : ON_HURT) {
            try {
                l.onHurt(info);
            } catch (Throwable t) {
                Constants.LOG.error("Numen perception hurt listener failed", t);
            }
        }
    }

    public static void fireEffectChange(NumenPlayer body, MobEffectInstance effect, boolean added) {
        EffectInfo info = new EffectInfo(body, effect, added);
        for (EffectListener l : ON_EFFECT_CHANGE) {
            try {
                l.onEffectChange(info);
            } catch (Throwable t) {
                Constants.LOG.error("Numen perception effect listener failed", t);
            }
        }
    }

    public static void fireItemPickup(NumenPlayer body, ItemStack stack, int count) {
        PickupInfo info = new PickupInfo(body, stack, count);
        for (PickupListener l : ON_ITEM_PICKUP) {
            try {
                l.onItemPickup(info);
            } catch (Throwable t) {
                Constants.LOG.error("Numen perception pickup listener failed", t);
            }
        }
    }
}
