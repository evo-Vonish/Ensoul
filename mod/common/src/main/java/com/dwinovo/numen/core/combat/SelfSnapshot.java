package com.dwinovo.numen.core.combat;

/**
 * An immutable, Minecraft-free snapshot of the bot's own combat-relevant state, handed to
 * {@link EngagementAssessor#assess}. Built by {@link McCombatAdapter} from a live {@code NumenPlayer}; the
 * acceptance runner builds it directly. <strong>Never imports any Minecraft type.</strong>
 *
 * @param hp             current health
 * @param maxHp          max health
 * @param armorPoints    {@code getArmorValue()} (floor of the ARMOR attribute), 0..20
 * @param armorToughness {@code getAttributeValue(ARMOR_TOUGHNESS)}
 * @param ownDps         sustained full-charge weapon DPS (§1.5 table × critFactor), resolved by the adapter
 * @param hasBow         a bow (or crossbow) is available to kite with
 * @param foodLevel      {@code getFoodData().getFoodLevel()} — sprint (and thus flight) needs food &gt; 6 (§1.5)
 */
public record SelfSnapshot(
        double hp,
        double maxHp,
        double armorPoints,
        double armorToughness,
        double ownDps,
        boolean hasBow,
        int foodLevel) {

    public double hpFrac() {
        return maxHp <= 0.0 ? 0.0 : hp / maxHp;
    }

    /** Sprint requires food &gt; 6 (FoodData.java:93) — below that, flight is not physically possible. */
    public boolean canSprint() {
        return foodLevel > 6;
    }
}
