package com.dwinovo.numen.core.combat;

/**
 * Central, single-source-of-truth constants for the engagement model (§3.4 proposed defaults). Every knob the
 * assessor reads lives here so calibration is one edit, not a scavenger hunt. Pure core — no Minecraft types.
 *
 * <p><strong>Calibration note.</strong> §3.6 flags the biggest real-world risk as the movement / flee-viability
 * buckets (§1.6) and {@link #MELEE_CADENCE_NORMAL} — both to be validated in-game (边玩边验收). The armor /
 * DPS / HP numbers are code-derived (§1.1/§1.3/§1.5) and are NOT calibration risks.
 */
public final class Tunables {

    private Tunables() {}

    /** Engage only if I out-last by 50%+ (crit variance, pathing lag) — the prior-art damage-race margin.
     *  Applied to confidence banding and {@code whatWouldFlip} analysis (see {@link EngagementAssessor}). */
    public static final double SAFETY_FACTOR = 1.5;

    /** Below this fraction of max HP, bias out of clean engages (a wounded body should not start a brawl). */
    public static final double HP_FLOOR = 0.30;

    /** A melee threat within this many blocks can land a hit within ~1 s → it counts toward frontage {@code k}.
     *  = MELEE_REACH 3.5 + one step. */
    public static final double FRONTAGE_RADIUS = 4.5;

    /** Geometry cap on how many attackers physically fit around one body in the open (linear-law {@code k}). */
    public static final int MAX_SURROUND = 4;

    /** The bot's own melee reach — matches hunt / the reflex ({@code Reflexes.MELEE_RANGE}). */
    public static final double MELEE_REACH = 3.5;

    /** Mob {@code MeleeAttackGoal} lands ~one hit/s (≈20 t cooldown) on normal. */
    public static final double MELEE_CADENCE_NORMAL = 1.0;
    /** Hard difficulty tightens attack delays (rate only — the per-hit number is unchanged, §1.2). */
    public static final double MELEE_CADENCE_HARD = 1.3;

    /** Inside this radius a creeper's blast disk is lethal (uncharged doubleRadius 6 + margin; matches the
     *  reflex fusing radius 7). Never enters the damage race — it is a keep-out. */
    public static final double CREEPER_BLAST_SAFE = 8.0;

    /** Minimum stand-off for bow kiting. */
    public static final double KITE_MIN_RANGE = 5.0;

    /** Set 1.3 only when a jump-crit is feasible and safe; the deterministic default assumes flat full-charge. */
    public static final double CRIT_FACTOR = 1.0;

    /** A {@link ThreatClass#NEVER_ENGAGE} mob anywhere within this radius forces an unconditional flee. */
    public static final double NEVER_ENGAGE_RADIUS = 40.0;

    /** Iron armour used by {@code whatWouldFlip} to test the "需要铁甲" counterfactual (§1.5 iron set = 15 pts). */
    public static final int IRON_ARMOR_POINTS = 15;

    // ---- armor math constants (§1.1, CombatRules.java:10–13) ----
    public static final double ARMOR_MAX = 20.0;
    public static final double ARMOR_PROTECTION_DIVIDER = 25.0;
    public static final double ARMOR_BASE_TOUGHNESS_DIVIDER = 2.0;
    public static final double ARMOR_TOUGHNESS_DIVISOR = 4.0;
    public static final double ARMOR_MIN_RATIO = 0.2;
}
