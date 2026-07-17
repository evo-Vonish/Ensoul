package com.dwinovo.numen.core.combat;

/**
 * The special-class of a single threat (§3.3). Drives {@code classify()} and selects the per-mob
 * combat constants in {@link ThreatTable}. Pure core — no Minecraft types.
 *
 * <ul>
 *   <li>{@link #NORMAL_MELEE} — zombie / husk / drowned … : in the damage race, outrunnable.</li>
 *   <li>{@link #FAST_MELEE} — baby zombie / spider / cave spider : in the race, but {@code fleeViable=false}
 *       (they climb / leap / out-sprint you).</li>
 *   <li>{@link #RANGED} — skeleton / stray / bogged / pillager / witch : incoming gated by range + line of sight;
 *       KITE-close or AVOID.</li>
 *   <li>{@link #EXPLOSIVE} — creeper : a keep-out disk, never melee-trade; KITE if a bow is held else FLEE.</li>
 *   <li>{@link #HEAVY_HITTER} — iron golem / vindicator : engage only above a gear threshold; one-shot guarded.</li>
 *   <li>{@link #NEVER_ENGAGE} — warden / wither : unconditional FLEE.</li>
 *   <li>{@link #NEUTRAL} — enderman / iron golem (unprovoked) : ignored unless it is the recorded attacker.</li>
 * </ul>
 */
public enum ThreatClass {
    NORMAL_MELEE,
    FAST_MELEE,
    RANGED,
    EXPLOSIVE,
    HEAVY_HITTER,
    NEVER_ENGAGE,
    NEUTRAL
}
