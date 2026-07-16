package com.dwinovo.numen.core.combat;

/**
 * The authoritative combat arithmetic (§1.1 armor, §1.5 weapon DPS, creeper blast). Every number here was read
 * from the decompiled 26.1.2 sources; the citations are in the design doc. Pure static math — no Minecraft
 * types, no state, no RNG (凡承重,必机械).
 */
public final class CombatMath {

    private CombatMath() {}

    /**
     * Fraction of a single hit removed by armor (§1.1, {@code CombatRules.getDamageAfterAbsorb}). The
     * {@code -rawHit/toughnessDivider} term is why armor DEGRADES against big single hits (golem / warden punch
     * through); toughness (2 + tough/4) softens that degradation. Returns a value in [0, 0.8].
     *
     * <pre>
     * toughnessDivider = 2 + toughness/4
     * realArmor        = clamp(armorPoints - rawHit/toughnessDivider, armorPoints*0.2, 20)
     * reduction        = realArmor / 25
     * </pre>
     *
     * @param rawHit      the incoming pre-armor hit size (matters — armor degrades per hit-size)
     * @param armorPoints {@code getArmorValue()} = floor(ARMOR attribute), 0..20
     * @param toughness   {@code getAttributeValue(ARMOR_TOUGHNESS)}
     */
    public static double armorReduction(double rawHit, double armorPoints, double toughness) {
        if (armorPoints <= 0.0) return 0.0;
        double divider = Tunables.ARMOR_BASE_TOUGHNESS_DIVIDER + toughness / Tunables.ARMOR_TOUGHNESS_DIVISOR;
        double realArmor = armorPoints - rawHit / divider;
        double floor = armorPoints * Tunables.ARMOR_MIN_RATIO;
        realArmor = clamp(realArmor, floor, Tunables.ARMOR_MAX);
        return realArmor / Tunables.ARMOR_PROTECTION_DIVIDER;
    }

    /** A raw hit after armor absorption (§1.1). Enchantment / resistance are a separate second stage — v1 ignores
     *  them (noted in the design doc). */
    public static double reduce(double rawHit, double armorPoints, double toughness) {
        return rawHit * (1.0 - armorReduction(rawHit, armorPoints, toughness));
    }

    /**
     * Effective HP against a representative incoming hit (§1.1 / prior-art {@code eHP = HP/(1-reduction)}). Armor
     * is applied ONCE — here, through eHP — so a survival estimate {@code eHP / rawIncomingDps} does not
     * double-count it (equivalently {@code HP / reducedIncomingDps}).
     */
    public static double effectiveHp(double hp, double armorPoints, double toughness, double representativeHit) {
        double r = armorReduction(representativeHit, armorPoints, toughness);
        return r >= 1.0 ? Double.POSITIVE_INFINITY : hp / (1.0 - r);
    }

    /** Melee attack cadence (hits/s) by difficulty — §1.2/§3.4: difficulty tunes the RATE only, never per-hit. */
    public static double meleeCadence(Difficulty difficulty) {
        return difficulty == Difficulty.HARD ? Tunables.MELEE_CADENCE_HARD : Tunables.MELEE_CADENCE_NORMAL;
    }

    /**
     * Sustained full-charge DPS of a held weapon (§1.5 table). Full-charge (per-hit × atk/s) — the bot always
     * swings at ≥0.95 charge (the reflex already gates it), so spam-scaling never applies.
     */
    public static double weaponDps(WeaponType w) {
        return w.dps;
    }

    /**
     * Point-blank-max creeper blast damage (§1.1, uncharged radius 3 → doubleRadius 6). Never enters the damage
     * race — used only to justify the keep-out disk. Exposure 1, dist 0 → {@code (1+1)/2 * 7 * 6 + 1 = 43}.
     */
    public static double creeperMaxBlast(boolean charged) {
        double radius = charged ? 6.0 : 3.0;
        double doubleRadius = radius * 2.0;
        double pow = 1.0;   // dist 0, exposure 1
        return (pow * pow + pow) / 2.0 * 7.0 * doubleRadius + 1.0;
    }

    static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * The §1.5 weapon-DPS table as a pure enum (per-hit × full-charge atk/s). The adapter maps a Minecraft
     * {@code Item} to one of these; the acceptance runner references them directly.
     */
    public enum WeaponType {
        FIST(1.0),               // bare hand (player base 1.0 × ~1 swing) — never chosen over a real weapon
        WOOD_SWORD(6.4),
        GOLD_SWORD(6.4),
        STONE_SWORD(8.0),
        COPPER_SWORD(8.0),
        IRON_SWORD(9.6),
        DIAMOND_SWORD(11.2),
        NETHERITE_SWORD(12.8),
        WOOD_AXE(7.2),
        STONE_AXE(7.2),
        GOLD_AXE(7.2),
        IRON_AXE(8.1),
        DIAMOND_AXE(9.0),
        NETHERITE_AXE(10.0);

        public final double dps;

        WeaponType(double dps) {
            this.dps = dps;
        }
    }
}
