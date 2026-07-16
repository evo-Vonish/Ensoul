package com.dwinovo.numen.core.combat;

/**
 * An immutable, Minecraft-free snapshot of one nearby hostile, handed to {@link EngagementAssessor#assess}.
 * Built by {@link McCombatAdapter} from a scan entry; the acceptance runner builds it directly.
 * <strong>Never imports any Minecraft type.</strong>
 *
 * <p>{@link #typeKey} is the registry path of the entity type (e.g. {@code "zombie"}, {@code "iron_golem"},
 * {@code "creeper"}) — the key {@link ThreatTable} looks combat constants up by. Current {@link #hp} comes from
 * the live scan (a wounded mob has less), so TTK reflects reality while damage constants come from the table.
 *
 * @param id       the entity's runtime id (echoed into {@link ThreatNote})
 * @param typeKey  registry path of the entity type; {@code "baby_zombie"} is synthesised by the adapter when a
 *                 zombie {@code isBaby()} (a baby is FAST_MELEE and cannot be outrun)
 * @param distance blocks from the bot
 * @param hp       current health (drives TTK)
 * @param maxHp    max health (diagnostic)
 * @param provoked whether this mob is the recorded attacker — the only reason a NEUTRAL (enderman / unprovoked
 *                 iron golem) is treated as a threat at all
 */
public record ThreatSnapshot(
        int id,
        String typeKey,
        double distance,
        double hp,
        double maxHp,
        boolean provoked) {

    /** Convenience for tests / adapter: an unprovoked mob. */
    public static ThreatSnapshot of(int id, String typeKey, double distance, double hp, double maxHp) {
        return new ThreatSnapshot(id, typeKey, distance, hp, maxHp, false);
    }
}
