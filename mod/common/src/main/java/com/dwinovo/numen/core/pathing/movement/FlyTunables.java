package com.dwinovo.numen.core.pathing.movement;

/**
 * Creative-flight tunables — the parameter table of creative-motion design v1
 * ("第二波"). Values are the blueprint's researched hard data: normal creative
 * flight tops out ≈0.506 b/t (10 b/s), sprint-fly ≈1.01 b/t, so the per-tick
 * velocity vector we write (the executor drives flight by
 * {@code setDeltaMovement} every tick, inputs zeroed) stays at or under what a
 * real creative player reaches. Lives beside {@link Movement} (blueprint slated
 * {@code pathing/util/}; kept in the movement package with the other
 * flight-wave types).
 */
public final class FlyTunables {

    private FlyTunables() {}

    // ---- speeds (blocks per tick, written directly as the velocity vector) ----

    /** Horizontal cruise speed for a normal (speed &lt; 1.0) navigation. */
    public static final double CRUISE_SPEED = 0.5;
    /** Horizontal cruise for a sprint-speed navigation (speed ≥ 1.0) — ≈ sprint-fly. */
    public static final double SPRINT_CRUISE_SPEED = 1.0;
    /** Vertical speed cap (both climb and descent) — the blueprint's 0.375 b/t. */
    public static final double VERTICAL_SPEED = 0.375;
    /** Creep speed while tunnelling through a wall (insta-break corridor). */
    public static final double BREAKTHROUGH_CREEP_SPEED = 0.2;

    // ---- take-off / cruise shape ----

    /** Blocks to climb straight up on take-off before cruising (巡航高度偏好 +2~4)
     *  — skipped when the direct corridor is already clear (e.g. flying DOWN into
     *  a canyon) or headroom runs out (a cave take-off). */
    public static final int TAKEOFF_CLIMB_BLOCKS = 3;
    /** How far ahead (blocks) the per-tick corridor raycast checks — obstructions
     *  beyond this are dealt with when we get closer (also keeps every world read
     *  inside comfortably-loaded chunks around the body). */
    public static final double CORRIDOR_LOOKAHEAD = 16.0;

    // ---- arrival / hover ----

    /** Horizontal arrive tolerance (blueprint: 水平 &lt; 0.35). */
    public static final double ARRIVE_HORIZONTAL = 0.35;
    /** Vertical arrive tolerance (blueprint: |Δy| &lt; 0.35). */
    public static final double ARRIVE_VERTICAL = 0.35;
    /** Inside this distance of the aim point the body just hovers (no micro-jitter). */
    public static final double HOVER_DEADZONE = 0.15;
    /** 3D distance at which an avoid-route waypoint counts as passed. */
    public static final double WAYPOINT_REACH = 0.9;
    /** Approach deceleration: commanded speed = min(cruise, dist × this) near the
     *  aim point — the flight twin of the ground executor's path-end sprint ease-off,
     *  so the body never overshoots the hover point. */
    public static final double APPROACH_DECEL_PER_BLOCK = 0.3;

    // ---- avoidance (the independent lightweight 3D A*) ----

    /** First-attempt search radius (Chebyshev box around the body). */
    public static final int AVOID_RADIUS = 16;
    /** Widened retry radius when the tight search fails around protected blocks. */
    public static final int AVOID_RADIUS_WIDE = 32;
    /** Node-expansion cap for the tight search (bounds the on-tick cost). */
    public static final int AVOID_NODE_CAP = 4096;
    /** Node-expansion cap for the widened retry. */
    public static final int AVOID_NODE_CAP_WIDE = 10000;
    /** Upward edges get this discount — the sky-preference that routes OVER obstacles. */
    public static final double FLY_COST_UP_DISCOUNT = 0.85;
    /** Downward edges get this surcharge (don't dive into holes gratuitously). */
    public static final double FLY_COST_DOWN_SURCHARGE = 1.15;
    /** Water cells cost this multiple — flyable in extremis, avoided when air exists. */
    public static final double FLY_COST_WATER_MULT = 4.0;
    /** A partial avoid route must end at least this many blocks closer to the aim
     *  than the start, or it isn't progress and the planner reports no route. */
    public static final double MIN_AVOID_PROGRESS = 2.0;
    /** Detour episodes (avoid replans + breakthrough escalations) before the flight
     *  honestly gives up and the nav falls back to ground pathing. */
    public static final int MAX_AVOID_EPISODES = 8;

    // ---- watchdogs ----

    /** Commanded-but-not-moving ticks before the cruise treats itself as blocked
     *  (catches the lateral clips the two-ray corridor check can miss). */
    public static final int STALL_TICKS = 30;
    /** Breakthrough ticks with no block broken and no motion before giving up. */
    public static final int BREAKTHROUGH_STALL_TICKS = 80;
}
