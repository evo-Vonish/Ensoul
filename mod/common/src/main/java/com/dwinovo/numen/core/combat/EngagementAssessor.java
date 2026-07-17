package com.dwinovo.numen.core.combat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The engagement decision engine (§3.2 pseudocode, faithfully). Given a Minecraft-free snapshot of the bot and
 * the nearby hostiles, it returns a deterministic {@link EngagementAssessment}: ENGAGE / KITE / FLEE / AVOID /
 * CORNERED, with the TTK/TTD numbers, frontage {@code k}, a Chinese {@code limitingFactor}, and a per-threat
 * breakdown. No Minecraft types, no LLM, no RNG (凡承重,必机械) — the same inputs always yield the same verdict.
 *
 * <h2>Decision model</h2>
 * The literal {@code ttdMe ≥ SAFETY·ttkClear} rule in §3.2 assumes a CONSTANT incoming DPS for the whole fight,
 * which under-counts multi-mob brawls (as you kill mobs, {@code k} drops and incoming falls) and cannot reproduce
 * the barely-winnable §3.5 scenarios (3b, 8, 10). This engine instead runs the honest Lanchester-linear
 * <em>decreasing-k sequential-clear simulation</em> ({@link #simulate}) as the hard engage gate — strictly more
 * accurate, still pure arithmetic — and uses {@code ttdMe}/{@code ttkClear}/{@code SAFETY_FACTOR} for the record
 * fields, confidence banding, and the {@code whatWouldFlip} counterfactuals. This is the one intentional
 * deviation from the pseudocode; it is what makes all 13 acceptance decisions come out right.
 */
public final class EngagementAssessor {

    private EngagementAssessor() {}

    /** One threat after table lookup + armor-reduction, ready for the damage race. */
    private static final class Resolved {
        final ThreatSnapshot snap;
        final ThreatTable.MobStats stats;
        final ThreatClass cls;
        final double reducedPerHitDps;   // armor-reduced per-hit × cadence (melee) — its incoming contribution
        final double reducedMaxHit;      // armor-reduced maximum single hit — for the one-shot guard

        Resolved(ThreatSnapshot snap, ThreatTable.MobStats stats, ThreatClass cls,
                 double reducedPerHitDps, double reducedMaxHit) {
            this.snap = snap;
            this.stats = stats;
            this.cls = cls;
            this.reducedPerHitDps = reducedPerHitDps;
            this.reducedMaxHit = reducedMaxHit;
        }
    }

    private record SimResult(boolean survivesFull, boolean survivesThin, double leftoverFull, double cumDmg) {}

    /**
     * Assess whether to fight the given hostiles. The main entry point (consumer a: reflex, consumer c: tool).
     *
     * @param self       the bot's own combat state
     * @param threats    nearby hostiles (any passive / effectively-neutral entries are ignored)
     * @param difficulty world difficulty (tunes cadence / ranged rate only — never per-hit, §1.2)
     * @param terrain    frontage cap + choke availability ({@link Terrain#open()} in v1 reflex use)
     */
    public static EngagementAssessment assess(SelfSnapshot self, List<ThreatSnapshot> threats,
                                              Difficulty difficulty, Terrain terrain) {
        double cadence = CombatMath.meleeCadence(difficulty);

        // ---- resolve + drop effectively-neutral / passive ----
        List<Resolved> active = new ArrayList<>();
        for (ThreatSnapshot t : threats) {
            ThreatClass cls = ThreatTable.classify(t);
            if (cls == ThreatClass.NEUTRAL) continue;   // enderman / unprovoked golem — not a threat
            ThreatTable.MobStats s = ThreatTable.stats(t.typeKey());
            double reducedDps = CombatMath.reduce(s.meleePerHit(), self.armorPoints(), self.armorToughness()) * cadence;
            double reducedMax = CombatMath.reduce(s.maxHit(), self.armorPoints(), self.armorToughness());
            active.add(new Resolved(t, s, cls, reducedDps, reducedMax));
        }
        if (active.isEmpty()) {
            return new EngagementAssessment(Decision.AVOID, 0.0f, "无威胁",
                    Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 0, List.of());
        }

        // ---- 1. NEVER_ENGAGE guard (warden / wither within 40) ----
        for (Resolved r : active) {
            if (r.cls == ThreatClass.NEVER_ENGAGE && r.snap.distance() <= Tunables.NEVER_ENGAGE_RADIUS) {
                return terminal(Decision.FLEE, 0.99f, "禁止交战:" + r.snap.typeKey(),
                        Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 0, active, difficulty);
            }
        }

        // ---- 2. creeper keep-out (never in the damage race) ----
        Resolved nearestCreeper = null;
        for (Resolved r : active) {
            if (r.cls == ThreatClass.EXPLOSIVE && r.snap.distance() <= Tunables.CREEPER_BLAST_SAFE
                    && (nearestCreeper == null || r.snap.distance() < nearestCreeper.snap.distance())) {
                nearestCreeper = r;
            }
        }
        if (nearestCreeper != null) {
            if (self.hasBow()) {
                return terminal(Decision.KITE, 0.8f, "近距离风筝", Double.POSITIVE_INFINITY,
                        Double.POSITIVE_INFINITY, 0, active, difficulty);
            }
            if (self.canSprint()) {
                return terminal(Decision.FLEE, 0.95f, "苦力怕爆炸,立即撤离", Double.POSITIVE_INFINITY,
                        Double.POSITIVE_INFINITY, 0, active, difficulty);
            }
            return terminal(Decision.CORNERED, 0.6f, "苦力怕近身且无法脱离", Double.POSITIVE_INFINITY,
                    Double.POSITIVE_INFINITY, 0, active, difficulty);
        }

        // ---- 3. split melee vs ranged ----
        List<Resolved> melee = new ArrayList<>();
        List<Resolved> ranged = new ArrayList<>();
        for (Resolved r : active) {
            if (r.cls == ThreatClass.NORMAL_MELEE || r.cls == ThreatClass.FAST_MELEE
                    || r.cls == ThreatClass.HEAVY_HITTER) {
                melee.add(r);
            } else if (r.cls == ThreatClass.RANGED) {
                ranged.add(r);
            }
        }

        // ---- ranged-only (scenario 4: skeleton, no melee pressure yet) ----
        if (melee.isEmpty()) {
            return decideRangedOnly(self, ranged, difficulty, active);
        }

        // ---- 4. frontage k + incoming (constant-k, for the record fields) ----
        melee.sort(Comparator.comparingDouble(r -> r.snap.distance()));
        int k = 0;
        for (Resolved r : melee) if (r.snap.distance() <= Tunables.FRONTAGE_RADIUS) k++;
        int frontageK = Math.min(Math.min(k, terrain.frontageCap()), Tunables.MAX_SURROUND);
        int kEff = Math.max(frontageK, melee.isEmpty() ? 0 : Math.min(1, melee.size()));   // ≥1 if any melee

        double repHit = 0.0;
        double rawIncoming = 0.0;
        for (int i = 0; i < melee.size() && i < kEff; i++) {
            Resolved r = melee.get(i);
            rawIncoming += r.stats.meleePerHit() * cadence;
            repHit = Math.max(repHit, r.stats.meleePerHit());
        }
        for (Resolved r : ranged) {
            if (r.snap.distance() <= r.stats.rangeBlocks()) {
                rawIncoming += r.stats.rangedDps(difficulty);
                repHit = Math.max(repHit, r.stats.maxHit());
            }
        }
        if (repHit <= 0.0) repHit = 1.0;

        double eHp = CombatMath.effectiveHp(self.hp(), self.armorPoints(), self.armorToughness(), repHit);
        double ttdMe = rawIncoming <= 0.0 ? Double.POSITIVE_INFINITY : eHp / rawIncoming;

        double totalMeleeHp = 0.0;
        for (Resolved r : melee) totalMeleeHp += r.snap.hp();
        double ttkClear = self.ownDps() <= 0.0 ? Double.POSITIVE_INFINITY : totalMeleeHp / self.ownDps();

        // ---- 5. one-shot / heavy-hitter guard (replaces the crude static 0.25 rule) ----
        double maxSingleHit = 0.0;
        for (Resolved r : melee) if (r.snap.distance() <= Tunables.FRONTAGE_RADIUS || melee.size() == 1) {
            maxSingleHit = Math.max(maxSingleHit, r.reducedMaxHit);
        }
        boolean biasFlee = maxSingleHit >= self.hp();

        boolean packFleeViable = self.canSprint() && melee.stream().allMatch(r -> r.stats.fleeViable());
        boolean belowFloor = self.hpFrac() < Tunables.HP_FLOOR;

        SimResult sim = simulate(melee, self, cadence, terrain.frontageCap());

        // ---- 6. decide ----
        List<ThreatNote> notes = buildNotes(active, melee, ranged, kEff, difficulty);

        // one-shot risk trumps everything: never trade blows with a lethal single hit
        if (biasFlee) {
            if (packFleeViable) {
                return new EngagementAssessment(Decision.FLEE, 0.99f, "一击致命风险", ttkClear, ttdMe, frontageK, notes);
            }
            if (sim.survivesFull()) {
                return new EngagementAssessment(Decision.ENGAGE, 0.5f, "一击致命且无法逃离,尚可一战",
                        ttkClear, ttdMe, frontageK, notes);
            }
            return new EngagementAssessment(Decision.CORNERED, 0.9f, "一击致命且无法逃离",
                    ttkClear, ttdMe, frontageK, notes);
        }

        // clean win: survive clearing the whole pack (honest decreasing-k), and not already below the HP floor
        if (sim.survivesFull() && !belowFloor) {
            double leftoverFrac = clamp01(sim.leftoverFull() / self.hp());
            float conf = confEngage(leftoverFrac);
            String limiting = packFleeViable ? "" : "无法逃跑,评估可战胜";
            return new EngagementAssessment(Decision.ENGAGE, conf, limiting, ttkClear, ttdMe, frontageK, notes);
        }

        if (packFleeViable) {
            // thin the pack to a fleeable remainder, then break off — still a net win (scenario 3b)
            if (!belowFloor && melee.size() >= 2 && sim.survivesThin()) {
                return new EngagementAssessment(Decision.ENGAGE, 0.6f, "", ttkClear, ttdMe, frontageK, notes);
            }
            if (self.hasBow()) {
                return new EngagementAssessment(Decision.KITE, 0.6f, "远程放风筝消耗", ttkClear, ttdMe, frontageK, notes);
            }
            if (melee.size() > 1 && terrain.hasChoke()) {
                return new EngagementAssessment(Decision.KITE, 0.5f, "退守隘口降低受击数", ttkClear, ttdMe, frontageK, notes);
            }
            float conf = confFlee(sim, self);
            return new EngagementAssessment(Decision.FLEE, conf,
                    whatWouldFlip(self, melee, cadence, terrain), ttkClear, ttdMe, frontageK, notes);
        }

        // flight not viable (spider / baby / no sprint)
        if (sim.survivesFull()) {
            return new EngagementAssessment(Decision.ENGAGE, 0.6f, "无法逃跑,战斗可行", ttkClear, ttdMe, frontageK, notes);
        }
        float conf = confCornered(sim, self);
        return new EngagementAssessment(Decision.CORNERED, conf, "既不能战胜也不能逃离,需要脱困手段",
                ttkClear, ttdMe, frontageK, notes);
    }

    // ---------------------------------------------------------------- ranged-only

    private static EngagementAssessment decideRangedOnly(SelfSnapshot self, List<Resolved> ranged,
                                                         Difficulty difficulty, List<Resolved> active) {
        double incoming = 0.0;
        double repHit = 1.0;
        double totalHp = 0.0;
        boolean anyInRange = false;
        for (Resolved r : ranged) {
            totalHp += r.snap.hp();
            if (r.snap.distance() <= r.stats.rangeBlocks()) {
                anyInRange = true;
                incoming += r.stats.rangedDps(difficulty);
                repHit = Math.max(repHit, r.stats.maxHit());
            }
        }
        double eHp = CombatMath.effectiveHp(self.hp(), self.armorPoints(), self.armorToughness(), repHit);
        double ttdMe = incoming <= 0.0 ? Double.POSITIVE_INFINITY : eHp / incoming;
        double ttkClear = self.ownDps() <= 0.0 ? Double.POSITIVE_INFINITY : totalHp / self.ownDps();
        List<ThreatNote> notes = buildNotes(active, List.of(), ranged, 0, difficulty);

        if (anyInRange) {
            if (self.hasBow()) {
                return new EngagementAssessment(Decision.KITE, 0.6f, "远程对射", ttkClear, ttdMe, 0, notes);
            }
            if (self.hpFrac() > Tunables.HP_FLOOR) {
                // close the gap under cover to negate the bow (scenario 4)
                return new EngagementAssessment(Decision.KITE, 0.5f, "需要贴近或掩护规避远程", ttkClear, ttdMe, 0, notes);
            }
            return new EngagementAssessment(Decision.AVOID, 0.5f, "远程压制且血量不足,绕行规避", ttkClear, ttdMe, 0, notes);
        }
        return new EngagementAssessment(Decision.AVOID, 0.4f, "远程威胁在射程外,绕行规避", ttkClear, ttdMe, 0, notes);
    }

    // ---------------------------------------------------------------- honest sim

    /**
     * The decreasing-k sequential-clear simulation (Lanchester linear law). Kill highest-DPS-first (optimal
     * engage order — thins incoming fastest); each phase the active attackers are capped by {@code frontageCap}
     * and shrink as mobs die. Returns whether the bot survives clearing the whole pack, whether it survives
     * thinning to a single fleeable remainder, and the leftover HP.
     */
    private static SimResult simulate(List<Resolved> pack, SelfSnapshot self, double cadence, int frontageCap) {
        int n = pack.size();
        if (n == 0) return new SimResult(true, true, self.hp(), 0.0);
        if (self.ownDps() <= 0.0) {
            return new SimResult(false, false, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
        }
        // sort by incoming-DPS desc (tie: lower HP first) — the order the bot should actually kill in
        List<Resolved> order = new ArrayList<>(pack);
        order.sort(Comparator.comparingDouble((Resolved r) -> r.reducedPerHitDps).reversed()
                .thenComparingDouble(r -> r.snap.hp()));

        double cumDmg = 0.0;
        double dmgAfterKillNMinus1 = 0.0;   // damage taken by the time (n-1) mobs are dead → thinned to 1
        for (int idx = 0; idx < n; idx++) {
            int alive = n - idx;
            int activeAttackers = Math.min(alive, frontageCap);
            double phaseIncoming = 0.0;
            for (int j = idx; j < idx + activeAttackers && j < n; j++) {
                phaseIncoming += order.get(j).reducedPerHitDps;   // the frontmost (highest-DPS) still alive
            }
            double killTime = order.get(idx).snap.hp() / self.ownDps();
            cumDmg += phaseIncoming * killTime;
            if (idx == n - 2) dmgAfterKillNMinus1 = cumDmg;
        }
        boolean survivesFull = cumDmg <= self.hp();
        boolean survivesThin = n >= 2 && dmgAfterKillNMinus1 <= self.hp();
        return new SimResult(survivesFull, survivesThin, self.hp() - cumDmg, cumDmg);
    }

    // ---------------------------------------------------------------- whatWouldFlip

    /** The single change that would flip a FLEE into an engageable position → the brain-facing limitingFactor. */
    private static String whatWouldFlip(SelfSnapshot self, List<Resolved> melee, double cadence, Terrain terrain) {
        // + iron armour
        SelfSnapshot ironed = new SelfSnapshot(self.hp(), self.maxHp(),
                Math.max(self.armorPoints(), Tunables.IRON_ARMOR_POINTS), self.armorToughness(),
                self.ownDps(), self.hasBow(), self.foodLevel());
        if (engageableUnder(ironed, melee, cadence, terrain)) return "需要铁甲";
        // full HP
        SelfSnapshot healed = new SelfSnapshot(self.maxHp(), self.maxHp(), self.armorPoints(), self.armorToughness(),
                self.ownDps(), self.hasBow(), self.foodLevel());
        if (engageableUnder(healed, melee, cadence, terrain)) return "血量不足";
        // fewer attackers (a choke that holds k at 1)
        if (melee.size() >= 2 && engageableUnder(self, melee, cadence, Terrain.choke(1))) return "敌人太多";
        return "实力不足,需更强装备";
    }

    /** Would the (possibly counterfactual) self survive-clear or survive-thin this pack? */
    private static boolean engageableUnder(SelfSnapshot self, List<Resolved> melee, double cadence, Terrain terrain) {
        // rebuild reduced dps for the counterfactual armour
        List<Resolved> re = new ArrayList<>(melee.size());
        for (Resolved r : melee) {
            double dps = CombatMath.reduce(r.stats.meleePerHit(), self.armorPoints(), self.armorToughness()) * cadence;
            double mx = CombatMath.reduce(r.stats.maxHit(), self.armorPoints(), self.armorToughness());
            re.add(new Resolved(r.snap, r.stats, r.cls, dps, mx));
        }
        // a lethal single hit is never engageable regardless of the race
        for (Resolved r : re) if (r.reducedMaxHit >= self.hp()) return false;
        SimResult sim = simulate(re, self, cadence, terrain.frontageCap());
        return sim.survivesFull() || (re.size() >= 2 && sim.survivesThin());
    }

    // ---------------------------------------------------------------- notes + confidence

    private static List<ThreatNote> buildNotes(List<Resolved> active, List<Resolved> melee, List<Resolved> ranged,
                                               int kEff, Difficulty difficulty) {
        List<ThreatNote> notes = new ArrayList<>(active.size());
        // which melee mobs are actually in contact (contribute) — the nearest kEff
        int contributing = 0;
        for (Resolved r : active) {
            double contributes = 0.0;
            boolean rangedGated = false;
            String note;
            switch (r.cls) {
                case NORMAL_MELEE, FAST_MELEE, HEAVY_HITTER -> {
                    boolean inFront = melee.indexOf(r) >= 0 && melee.indexOf(r) < kEff
                            && r.snap.distance() <= Tunables.FRONTAGE_RADIUS;
                    if (inFront) {
                        contributes = r.reducedPerHitDps;
                        contributing++;
                    }
                    note = switch (r.cls) {
                        case HEAVY_HITTER -> "重锤(高单发,注意一击致命)";
                        case FAST_MELEE -> "快速近战(无法逃脱)";
                        default -> inFront ? "近战接触中" : "近战接近中";
                    };
                }
                case RANGED -> {
                    boolean inRange = r.snap.distance() <= r.stats.rangeBlocks();
                    if (inRange) {
                        contributes = r.stats.rangedDps(difficulty);
                    } else {
                        rangedGated = true;
                    }
                    note = inRange ? "远程压制中" : "远程(在射程外)";
                }
                case EXPLOSIVE -> note = "苦力怕(禁区外)";
                case NEVER_ENGAGE -> note = "禁止交战";
                default -> note = "";
            }
            notes.add(new ThreatNote(r.snap.id(), r.snap.typeKey(), r.snap.distance(), r.snap.hp(),
                    r.cls, contributes, rangedGated, note));
        }
        return notes;
    }

    private static EngagementAssessment terminal(Decision d, float conf, String limiting, double ttk, double ttd,
                                                 int k, List<Resolved> active, Difficulty difficulty) {
        return new EngagementAssessment(d, conf, limiting, ttk, ttd, k,
                buildNotes(active, List.of(), List.of(), 0, difficulty));
    }

    private static float confEngage(double leftoverFrac) {
        return (float) CombatMath.clamp(0.5 + 0.5 * leftoverFrac, 0.5, 0.99);
    }

    private static float confFlee(SimResult sim, SelfSnapshot self) {
        if (Double.isInfinite(sim.cumDmg())) return 0.95f;
        double shortfall = clamp01((sim.cumDmg() - self.hp()) / Math.max(sim.cumDmg(), 1e-6));
        return (float) CombatMath.clamp(0.5 + 0.5 * shortfall, 0.5, 0.95);
    }

    private static float confCornered(SimResult sim, SelfSnapshot self) {
        if (Double.isInfinite(sim.cumDmg())) return 0.9f;
        double shortfall = clamp01((sim.cumDmg() - self.hp()) / Math.max(sim.cumDmg(), 1e-6));
        return (float) CombatMath.clamp(0.5 + 0.3 * shortfall, 0.4, 0.9);
    }

    private static double clamp01(double v) {
        return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v);
    }
}
