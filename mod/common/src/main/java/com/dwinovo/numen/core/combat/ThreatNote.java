package com.dwinovo.numen.core.combat;

/**
 * Per-threat line in an {@link EngagementAssessment} (§3.1 {@code perThreat}). Brain-facing detail for one mob:
 * how it was classified, what it contributes to incoming DPS, and whether a ranged threat is currently gated out
 * (out of range / no line of fire). Pure core.
 *
 * @param id              the mob's runtime id
 * @param type            registry path (the {@link ThreatSnapshot#typeKey})
 * @param dist            blocks from the bot
 * @param hp              current health
 * @param threatClass     the resolved {@link ThreatClass}
 * @param contributesDps  armor-reduced DPS this mob currently adds to incoming (0 if beyond frontage / gated)
 * @param rangedGated     true when a RANGED mob is out of range or has no line of fire, so it adds nothing now
 * @param note            short Chinese annotation for the brain
 */
public record ThreatNote(
        int id,
        String type,
        double dist,
        double hp,
        ThreatClass threatClass,
        double contributesDps,
        boolean rangedGated,
        String note) {
}
