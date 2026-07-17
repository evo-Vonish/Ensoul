package com.dwinovo.numen.core.perception;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

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

    // ---- §8 repeated-failure detector (see CorrectiveNotices) ----
    /**
     * Session-scope streak state per failure key ("{@code tool|message-prefix}"):
     * {@code [lastFailureTick, consecutiveCount]}. A streak resets once the gap since
     * the last identical failure exceeds the detector's window. Not persisted — a
     * repeated-failure pattern is a live-loop signal, dropped with the body like the
     * rest of this state.
     */
    final Map<String, long[]> failureStreaks = new HashMap<>();

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
    /**
     * Raw damage of the last hit from {@link #reflexAttacker} ({@code HurtInfo.amount}); v2
     * heavy-hitter seam. A single hit ≥25% of max HP makes the critical reflex flee unconditionally
     * (any distance, any HP) instead of trading blows — the iron-golem death. Reset with the episode.
     */
    float reflexLastHitDamage;

    // Reflex 2 (v4): threat-triggered assessment (刀①) — arm an engagement assessment on proximity, not just on
    // a landed hit or HP<40%. Refreshed only on the idle proximity-scan cadence, so it's a null-check per tick.
    /** Nearest hostile remembered by the idle proximity scan (arms the assessment before the first hit); null = none. */
    Entity reflexNearHostile;

    // Reflex 2 (v4): turtle-up (刀③) — the CORNERED escape hatch.
    /** Game time the next turtle-up is allowed (600t between burrows); persists across episodes (anti-abuse). */
    long reflexTurtleCooldownUntil;
    /** True once this critical episode has already turtled (one per episode); reset when the episode ends. */
    boolean reflexTurtledThisEpisode;

    // Reflex 3 (v2): creeper panic sprint — HP-independent, pre-explosion (the looting one-shot death).
    /**
     * Nearest creeper remembered by the 20t proximity scan; null = none nearby. Gates the per-tick
     * creeper distance check to near-zero cost (the live ref is only refreshed on the 20t cadence,
     * so the every-tick reflex is a null-check + one {@code distanceTo} only while a creeper is close).
     */
    Entity reflexCreeper;
    /** Creeper-sprint deadline (≤ now+40t) while sprinting away from a creeper; 0 = not creeper-fleeing. */
    long reflexCreeperFleeUntil;

    // ---- read-only threat view for the attention brain (core.look) — derives, never writes ----

    /**
     * How long after the last hit / sighting an attacker still counts as an "active" look-back threat.
     * Mirrors {@link Reflexes}' own {@code ATTACKER_GONE_TICKS} gone-timer so a long-finished engagement
     * stops pulling the gaze back (a creeper ref needs no such window — the 20t reflex scan nulls it the
     * moment the creeper leaves).
     */
    private static final long THREAT_ATTACKER_RECENT_TICKS = 100L;

    /**
     * The eye-height world point of the threat the reflex layer is currently tracking — creeper first (the
     * more urgent thing to keep an eye on), then a recently-seen living attacker — or {@code null} when no
     * threat is live. This is a pure read-only <em>derivation</em> over the existing reflex refs
     * ({@link #reflexCreeper}, {@link #reflexAttacker}, {@link #reflexAttackerSeenTick}) that {@link Reflexes}
     * already maintains: it reads what the spinal cord wrote and never writes anything itself. {@code now} is
     * the current game time, used only to age out a stale attacker. Server-thread only, like the rest of this
     * state. Consumed via {@link Perceptions#activeThreatEyePos(com.dwinovo.numen.entity.NumenPlayer)} so the
     * look brain (a different package) can glance back at danger without reaching into these fields directly.
     */
    Vec3 activeThreatEyePos(long now) {
        Entity c = reflexCreeper;
        if (c != null && c.isAlive() && !c.isRemoved()) {
            return c.getEyePosition();
        }
        Entity a = reflexAttacker;
        if (a != null && a.isAlive() && !a.isRemoved()
                && (now - reflexAttackerSeenTick) <= THREAT_ATTACKER_RECENT_TICKS) {
            return a.getEyePosition();
        }
        return null;
    }
}
