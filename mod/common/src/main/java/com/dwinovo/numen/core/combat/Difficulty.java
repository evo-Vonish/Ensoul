package com.dwinovo.numen.core.combat;

/**
 * World difficulty, the ONLY dial that touches the melee/ranged CADENCE (not per-hit damage — §1.2 verified
 * that 26.1.2 has no difficulty multiplier anywhere in the melee path). Affects melee attack cadence
 * ({@link CombatMath#meleeCadence}) and ranged fire rate ({@link ThreatTable.MobStats} normal vs hard DPS).
 *
 * <p>Pure core — the Minecraft {@code net.minecraft.world.Difficulty} is mapped to this by the adapter.
 */
public enum Difficulty {
    PEACEFUL,
    EASY,
    NORMAL,
    HARD
}
