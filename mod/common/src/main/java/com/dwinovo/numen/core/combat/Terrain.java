package com.dwinovo.numen.core.combat;

/**
 * Minecraft-free terrain context for one assessment. In v1 the reflex adapter passes {@link #open()} (it does no
 * terrain sensing — that is consumer b, the pathing wave); the acceptance runner passes a choke for the
 * back-to-wall scenario (§3.5 #8).
 *
 * @param frontageCap how many attackers can physically reach the bot at once given cover (a wall / choke lowers
 *                    this below {@link Tunables#MAX_SURROUND}; open field = MAX_SURROUND)
 * @param hasChoke    whether a defensible choke exists to strafe-and-strike from (enables the KITE fallback)
 */
public record Terrain(int frontageCap, boolean hasChoke) {

    /** Open field: full {@link Tunables#MAX_SURROUND} attackers, no choke. */
    public static Terrain open() {
        return new Terrain(Tunables.MAX_SURROUND, false);
    }

    /** A choke that limits the frontage to {@code cap} attackers (e.g. back-to-wall = 2). */
    public static Terrain choke(int cap) {
        return new Terrain(cap, true);
    }
}
