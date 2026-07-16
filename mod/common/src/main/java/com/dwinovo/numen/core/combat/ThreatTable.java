package com.dwinovo.numen.core.combat;

import java.util.Map;

/**
 * Per-mob combat constants (§1.3 stat table) keyed by entity registry path, plus the {@code classify()} logic
 * (§3.3 special-class table). Every number is code-derived from the 26.1.2 sources (citations in the design doc).
 * Pure core — no Minecraft types.
 *
 * <p><strong>Determinism.</strong> The iron golem's per-hit is the EXPECTED value 14.5 (used for the DPS / TTK
 * race), while its {@link MobStats#maxHit} is the MAXIMUM 21.5 (used only by the one-shot guard) — no RNG, just
 * the two ends of {@code 7.5–21.5} used where each is correct (凡承重,必机械).
 */
public final class ThreatTable {

    private ThreatTable() {}

    /**
     * Combat constants for one mob type.
     *
     * @param threatClass         behaviour class when it IS a threat (for a neutral, its provoked behaviour)
     * @param meleePerHit         expected per-hit melee damage — the DPS/TTK-race number (golem = 14.5 expected)
     * @param maxHit              maximum single-hit damage — the one-shot-guard number (golem = 21.5 max)
     * @param rangeBlocks         ranged reach (0 for a pure melee mob)
     * @param rangedDpsNormal     ranged DPS at normal difficulty (fire-rate gated)
     * @param rangedDpsHard       ranged DPS at hard difficulty
     * @param fleeViable          §1.6 bucket — false for climbers / leapers / +50%-speed (spider, baby, flyers)
     * @param neutralUntilProvoked true for a mob that is ignored unless it is the recorded attacker (enderman,
     *                            iron golem)
     */
    public record MobStats(
            ThreatClass threatClass,
            double meleePerHit,
            double maxHit,
            double rangeBlocks,
            double rangedDpsNormal,
            double rangedDpsHard,
            boolean fleeViable,
            boolean neutralUntilProvoked) {

        double rangedDps(Difficulty d) {
            return d == Difficulty.HARD ? rangedDpsHard : rangedDpsNormal;
        }
    }

    // Melee mobs (§1.3): default MAX_HEALTH 20, ATTACK_DAMAGE 3 unless noted. Ranged mobs carry range + DPS.
    private static final MobStats ZOMBIE      = new MobStats(ThreatClass.NORMAL_MELEE, 3.0, 3.0, 0, 0, 0, true,  false);
    private static final MobStats BABY_ZOMBIE = new MobStats(ThreatClass.FAST_MELEE,  3.0, 3.0, 0, 0, 0, false, false);
    private static final MobStats SKELETON    = new MobStats(ThreatClass.RANGED, 2.0, 4.0, 15.0, 2.0, 4.0, true,  false);
    private static final MobStats BOGGED      = new MobStats(ThreatClass.RANGED, 2.0, 4.0, 15.0, 2.0, 4.0, true,  false);
    private static final MobStats SPIDER      = new MobStats(ThreatClass.FAST_MELEE, 2.0, 2.0, 0, 0, 0, false, false);
    private static final MobStats CAVE_SPIDER = new MobStats(ThreatClass.FAST_MELEE, 2.0, 2.0, 0, 0, 0, false, false);
    private static final MobStats CREEPER     = new MobStats(ThreatClass.EXPLOSIVE, 0.0, 43.0, 0, 0, 0, true,  false);
    private static final MobStats ENDERMAN    = new MobStats(ThreatClass.NORMAL_MELEE, 7.0, 7.0, 0, 0, 0, false, true);
    private static final MobStats WITCH       = new MobStats(ThreatClass.RANGED, 0.0, 6.0, 10.0, 2.5, 2.5, true, false);
    private static final MobStats PILLAGER    = new MobStats(ThreatClass.RANGED, 5.0, 6.0, 8.0, 3.0, 3.0, true, false);
    private static final MobStats VINDICATOR  = new MobStats(ThreatClass.HEAVY_HITTER, 13.0, 13.0, 0, 0, 0, true, false);
    private static final MobStats IRON_GOLEM  = new MobStats(ThreatClass.HEAVY_HITTER, 14.5, 21.5, 0, 0, 0, true, true);
    private static final MobStats PHANTOM     = new MobStats(ThreatClass.NORMAL_MELEE, 6.0, 8.0, 0, 0, 0, false, false);
    private static final MobStats WARDEN      = new MobStats(ThreatClass.NEVER_ENGAGE, 30.0, 30.0, 20.0, 10.0, 10.0, false, false);
    private static final MobStats WITHER      = new MobStats(ThreatClass.NEVER_ENGAGE, 15.0, 20.0, 20.0, 8.0, 8.0, false, false);

    /** Conservative default for an unrecognised hostile — a zombie-like normal melee. */
    private static final MobStats DEFAULT_HOSTILE =
            new MobStats(ThreatClass.NORMAL_MELEE, 3.0, 4.0, 0, 0, 0, true, false);

    private static final Map<String, MobStats> TABLE = Map.ofEntries(
            Map.entry("zombie", ZOMBIE),
            Map.entry("husk", ZOMBIE),
            Map.entry("drowned", ZOMBIE),
            Map.entry("zombie_villager", ZOMBIE),
            Map.entry("zombified_piglin", ZOMBIE),
            Map.entry("baby_zombie", BABY_ZOMBIE),
            Map.entry("skeleton", SKELETON),
            Map.entry("stray", SKELETON),
            Map.entry("bogged", BOGGED),
            Map.entry("wither_skeleton", new MobStats(ThreatClass.HEAVY_HITTER, 8.0, 8.0, 0, 0, 0, true, false)),
            Map.entry("spider", SPIDER),
            Map.entry("cave_spider", CAVE_SPIDER),
            Map.entry("creeper", CREEPER),
            Map.entry("enderman", ENDERMAN),
            Map.entry("witch", WITCH),
            Map.entry("pillager", PILLAGER),
            Map.entry("vindicator", VINDICATOR),
            Map.entry("iron_golem", IRON_GOLEM),
            Map.entry("phantom", PHANTOM),
            Map.entry("vex", new MobStats(ThreatClass.NORMAL_MELEE, 9.0, 9.0, 0, 0, 0, false, false)),
            Map.entry("warden", WARDEN),
            Map.entry("wither", WITHER));

    /** Combat constants for a type key ({@code getType()} registry path), or a conservative melee default. */
    public static MobStats stats(String typeKey) {
        if (typeKey == null) return DEFAULT_HOSTILE;
        return TABLE.getOrDefault(typeKey, DEFAULT_HOSTILE);
    }

    /**
     * The EFFECTIVE class of a threat (§3.3): a {@code neutralUntilProvoked} mob (enderman / iron golem) reads as
     * {@link ThreatClass#NEUTRAL} — and is dropped from the fight — unless it is the recorded attacker.
     */
    public static ThreatClass classify(ThreatSnapshot t) {
        MobStats s = stats(t.typeKey());
        if (s.neutralUntilProvoked() && !t.provoked()) return ThreatClass.NEUTRAL;
        return s.threatClass();
    }
}
