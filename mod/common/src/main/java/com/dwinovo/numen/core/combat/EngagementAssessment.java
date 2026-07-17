package com.dwinovo.numen.core.combat;

import java.util.List;

/**
 * The output contract of the engagement engine (§3.1). A single immutable verdict the three consumers read:
 * (a) the reflex layer replaces its crude 25%-hit rule with {@link #decision} + {@link #ttkClear}/{@link #ttdMe};
 * (b) en-route pathing uses {@code decision ∈ {AVOID, FLEE}} as a soft cost; (c) the {@code assess_threat} brain
 * tool returns the whole record. Pure core — no Minecraft types, no LLM, no RNG.
 *
 * @param decision       the terminal verdict
 * @param confidence     [0,1]; how far the situation sits from the flip point (hysteresis / brain framing)
 * @param limitingFactor human/brain-facing Chinese reason (what would have to change to flip the verdict), e.g.
 *                       "需要铁甲" / "血量不足" / "一击致命风险" / "禁止交战:…"; empty on a clean ENGAGE
 * @param ttkClear       seconds to clear all reachable melee threats at own DPS (sequential — melee frontage)
 * @param ttdMe          seconds until I die at current incoming DPS (armor-reduced); +∞ if nothing can hit me
 * @param frontageK      number of threats that can hit me right now (Lanchester linear-law {@code k})
 * @param perThreat      per-mob breakdown
 */
public record EngagementAssessment(
        Decision decision,
        float confidence,
        String limitingFactor,
        double ttkClear,
        double ttdMe,
        int frontageK,
        List<ThreatNote> perThreat) {
}
