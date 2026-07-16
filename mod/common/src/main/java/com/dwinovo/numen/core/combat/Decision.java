package com.dwinovo.numen.core.combat;

/**
 * The five terminal verdicts of the {@link EngagementAssessor} (§3.1 output contract).
 *
 * <ul>
 *   <li>{@link #ENGAGE} — fight now; the damage race is winnable (honest decreasing-k survival sim).</li>
 *   <li>{@link #KITE} — fight, but from range / behind a choke (bow放风筝 or strafe-and-strike) to keep {@code k} low.</li>
 *   <li>{@link #FLEE} — break contact and run (the pack is outrunnable and the fight is lost).</li>
 *   <li>{@link #AVOID} — threats exist but none is engaged; reroute around them (consumer b path-cost).</li>
 *   <li>{@link #CORNERED} — neither fight nor flight is viable → escalation menu (pillar-up / water / owner-assist).</li>
 * </ul>
 *
 * <p>Part of the pure deterministic core — no Minecraft types, no LLM, no RNG (凡承重,必机械).
 */
public enum Decision {
    ENGAGE,
    KITE,
    FLEE,
    AVOID,
    CORNERED
}
