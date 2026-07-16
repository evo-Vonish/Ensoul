package com.dwinovo.numen.core.perception;

import net.minecraft.world.entity.Entity;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-companion L3 immediate-perception state (Wave B of the append-only world
 * cognition design). One instance lives per live {@link com.dwinovo.numen.entity.NumenPlayer},
 * keyed by companion UUID in {@link Perceptions#STATES}, created on first use
 * ({@code computeIfAbsent}) and dropped by the {@code CompanionLifecycle.onRemove}
 * seam when the body leaves the world.
 *
 * <p>This is pure short-lived bookkeeping: cooldown clocks, batch buffers, and
 * edge-detection latches so a raw stream of world events becomes a small number
 * of meaningful notes. It is <strong>server-thread only</strong> — the perception
 * seams ({@code PerceptionEvents}) and the poll pass ({@code Perceptions.tick})
 * both run on the server tick thread, so no synchronisation is needed (mirrors
 * {@code CompanionTickDispatcher}'s plain-map state). Nothing here is persisted;
 * immediate perception never enters the landmark map or the disk event log.
 */
final class PerceptionState {

    // ---- generic (kind, subject) cooldown clocks ----
    // Keyed "kind|subject" → the game time at which that subject may emit again.
    // Backs the rate-limit helper in Perceptions (proximity per-entity / per-type).
    private final Map<String, Long> cooldownUntil = new HashMap<>();

    /** True if "{@code key}" has never fired or its cooldown has elapsed by {@code now}. */
    boolean ready(String key, long now) {
        Long until = cooldownUntil.get(key);
        return until == null || now >= until;
    }

    /** Arm "{@code key}" so it stays silent until {@code now + cooldownTicks}. */
    void arm(String key, long now, int cooldownTicks) {
        cooldownUntil.put(key, now + cooldownTicks);
    }

    // ---- hurt burst window ----
    /** Game time the current 40-tick burst window closes; 0 = no window open. */
    long hurtWindowEnd;
    /** Hits counted in the current window (the first triggers the urgent alert). */
    int hurtCount;
    /** Remaining HP reported by the first hit of the window (the "from"). */
    float hurtHpFrom;
    /** Remaining HP after the most recent hit of the window (the "to"). */
    float hurtHpTo;
    /** Attacker label carried into the merged follow-up summary; null = unknown. */
    String hurtAttacker;
    /** Whether the &lt;25% HP urgent re-arm has already fired this window. */
    boolean hurtQuarterFired;

    // ---- item pickup batch ----
    /** Aggregated item display-name → count for the current 100-tick batch. */
    final Map<String, Integer> pickupBatch = new HashMap<>();
    /** Game time to flush the pickup batch; 0 = nothing buffered. */
    long pickupFlushAt;

    // ---- hunger edge latches (re-armed only after recovery above 10) ----
    boolean hungerLowFired;   // the ≤6 note already fired
    boolean hungerCritFired;  // the ≤2 urgent note already fired
    int lastHunger = -1;      // last polled food level (diagnostic)

    // ---- tool durability (mainhand snapshot from the previous poll) ----
    String lastMainhandKey = "";      // registry id of last-polled mainhand item ("" = empty)
    String lastMainhandName = "";     // its display name, for the "用坏了" break note
    int lastMainhandRemaining = -1;   // durability remaining (max - damage); -1 if not damageable
    boolean lastMainhandDamageable;
    /** Item id we've already low-durability warned about (so it's warned once, not every poll). */
    String durabilityWarnedKey;

    // ---- time-of-day / weather edges ----
    int lastTimePhase = -1;   // -1 unknown, 0 day, 1 night
    boolean lastThundering;
    boolean weatherInit;      // whether lastThundering is meaningful yet (skip the spawn edge)

    // ---- L2 regional observation (Wave C) ----
    /** Storage key of the region the companion was in last poll; null = not yet known. */
    String lastRegionKey;

    // ---- reflex layer (spinal cord) — see {@link Reflexes}. Server-thread only, like the rest. ----

    // Reflex 1: suffocation escape (the wall death).
    /** Game time the next block-break attempt is allowed (20t between attempts); 0 = ready. */
    long reflexSuffocateCooldownUntil;
    /** True while stuck-in-a-wall; gates the once-per-episode note (cleared when free again). */
    boolean reflexSuffocateEpisode;

    // Reflex 2: critical-HP fight-back / flee (the mauled-while-thinking death).
    /** Last LIVING attacker recorded from the hurt seam (fight-back target); null = none. */
    Entity reflexAttacker;
    /** Game time we were last hit by / last saw {@link #reflexAttacker} (arms the 100t gone-timer). */
    long reflexAttackerSeenTick;
    /** True while a critical-HP episode is live; gates the once-per-episode note. */
    boolean reflexCriticalEpisode;
    /** Game time the next reflex swing is allowed (~one hit per 12t); 0 = ready. */
    long reflexSwingUntil;
    /** Flee-drive deadline (≤ now+60t) while sprinting away; 0 = not currently fleeing. */
    long reflexFleeUntil;
}
