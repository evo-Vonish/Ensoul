package com.dwinovo.numen.core.combat;

import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * The ONE Minecraft-aware file in {@code core.combat}: it turns a live {@code NumenPlayer} + its surroundings
 * into the Minecraft-free {@link SelfSnapshot} / {@link ThreatSnapshot} the pure {@link EngagementAssessor}
 * consumes, and nothing else. Every other class in this package stays engine-agnostic and unit-testable by bare
 * {@code javac} — the boundary the design mandates (the core math never imports a Minecraft type).
 *
 * <p>Server-thread only (called from {@code Reflexes} and the {@code assess_threat} tool, both server-side).
 */
public final class McCombatAdapter {

    private McCombatAdapter() {}

    /**
     * Scan hostiles within {@code radius} of {@code self}, build the snapshots, and assess. The recorded attacker
     * (if any) is always included and flagged {@code provoked} — the only way a neutral (enderman / iron golem)
     * counts as a threat. Terrain is {@link Terrain#open()} in v1 (the reflex does no terrain sensing — that is
     * the pathing wave / consumer b).
     *
     * @param self     the companion body
     * @param provoked the recorded attacker to force-include as provoked, or {@code null}
     * @param radius   scan radius in blocks
     */
    public static EngagementAssessment assessNearby(NumenPlayer self, Entity provoked, double radius) {
        SelfSnapshot ss = selfSnapshot(self);
        List<ThreatSnapshot> threats = new ArrayList<>();

        AABB box = self.getBoundingBox().inflate(radius);
        boolean provokedSeen = false;
        for (Entity e : self.level().getEntities(self, box)) {
            if (!(e instanceof LivingEntity le) || !le.isAlive()) continue;
            boolean isProvoked = provoked != null && e == provoked;
            // Only hostiles enter the race; a neutral is included solely when it is the recorded attacker.
            if (!(e instanceof Monster) && !isProvoked) continue;
            threats.add(threatSnapshot(self, le, isProvoked));
            if (isProvoked) provokedSeen = true;
        }
        // The attacker may sit just outside the scan box (or be a non-Monster golem) — never drop it.
        if (provoked instanceof LivingEntity pl && pl.isAlive() && provoked.level() == self.level() && !provokedSeen) {
            threats.add(threatSnapshot(self, pl, true));
        }

        return EngagementAssessor.assess(ss, threats, difficulty(self), Terrain.open());
    }

    /** Build the bot's own combat snapshot from live attributes (nothing Minecraft leaks past this method). */
    public static SelfSnapshot selfSnapshot(NumenPlayer self) {
        double armorPoints = self.getArmorValue();
        double toughness = self.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        // Full-charge sustained DPS = per-hit ATTACK_DAMAGE (incl. the held weapon's modifier) × attacks/second
        // (the ATTACK_SPEED attribute). This reproduces the §1.5 table exactly (iron sword 6.0 × 1.6 = 9.6) and
        // tracks enchants / buffs for free; the bot always swings at full charge (the reflex gates ≥0.95).
        double ownDps = self.getAttributeValue(Attributes.ATTACK_DAMAGE)
                * self.getAttributeValue(Attributes.ATTACK_SPEED) * Tunables.CRIT_FACTOR;
        int food = self.getFoodData().getFoodLevel();
        return new SelfSnapshot(self.getHealth(), self.getMaxHealth(), armorPoints, toughness, ownDps,
                hasBow(self), food);
    }

    /** Build one threat snapshot; a baby of the zombie family is re-keyed to {@code baby_zombie} (FAST_MELEE). */
    public static ThreatSnapshot threatSnapshot(NumenPlayer self, LivingEntity e, boolean provoked) {
        String key = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
        if (e.isBaby() && (key.equals("zombie") || key.equals("husk")
                || key.equals("zombie_villager") || key.equals("drowned"))) {
            key = "baby_zombie";
        }
        return new ThreatSnapshot(e.getId(), key, self.distanceTo(e), e.getHealth(), e.getMaxHealth(), provoked);
    }

    private static boolean hasBow(NumenPlayer self) {
        return self.getInventory().contains(
                s -> s.getItem() == Items.BOW || s.getItem() == Items.CROSSBOW);
    }

    /** Map the MC world difficulty to the pure-core enum (cadence / ranged-rate dial only — §1.2). */
    public static Difficulty difficulty(NumenPlayer self) {
        net.minecraft.world.Difficulty d = self.level().getDifficulty();
        return switch (d) {
            case PEACEFUL -> Difficulty.PEACEFUL;
            case EASY -> Difficulty.EASY;
            case HARD -> Difficulty.HARD;
            default -> Difficulty.NORMAL;
        };
    }
}
