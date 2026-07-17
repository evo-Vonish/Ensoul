package com.dwinovo.numen.core.pathing.exec;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.util.BlockHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * The <strong>reflex-only navigation session</strong> — smart fleeing / hazard-seeking that drives the real
 * {@link PlayerNav} A* machine while <em>completely bypassing the tool / task layer</em>. Zero {@code tool_call},
 * zero {@link com.dwinovo.numen.core.task.TaskRecord}, zero result into the brain's context: the reflex layer
 * (spinal cord) uses this to move the body intelligently and only <em>informs</em> the brain afterwards with its
 * usual event blurb (informs-never-consults). It is the "智能逃跑" upgrade over the v1 straight-line
 * {@link InputDriver#stepToward} dash, which happily sprinted the body off a cliff or into a second mob pack.
 *
 * <h2>What it does</h2>
 * <ol>
 *   <li><b>Target choice (flee):</b> from a ring {@value #RING_MIN}–{@value #RING_MAX} blocks out, inside a cone
 *       pointing <em>away</em> from the threat centroid (owner-biased when the owner sits opposite the threats),
 *       gather the standable candidate cells and score each by a simple threat-influence decay (the combat
 *       research's influence-map idea, pure arithmetic — {@link #influence}); the least-threatened reachable cell
 *       wins. For a hazard seek (fire/lava → water) the caller hands an explicit target cell instead.</li>
 *   <li><b>Navigate:</b> wrap a {@link PlayerNav} to the chosen cell. A* unreachable → fall through to the next-best
 *       candidate; every candidate exhausted → {@link Status#FAILED}, and the caller falls back to the v1
 *       straight-line dash.</li>
 *   <li><b>Interrupt:</b> {@link #halt()} stops the nav and zeroes the inputs with no residue — the caller calls it
 *       the instant the episode ends, a higher reflex seizes the body, the owner interrupts, or the verdict flips.</li>
 * </ol>
 *
 * <p>Server-thread only, like the whole reflex layer. Holds no long-lived entity references (candidate cells are
 * frozen {@link BlockPos}), so a mid-flight body removal leaves nothing dangling once {@link #halt()} runs.
 */
public final class ReflexNav {

    public enum Status { RUNNING, ARRIVED, FAILED }

    // ---- flee-candidate geometry ----
    /** Inner radius of the candidate ring (blocks). */
    private static final int RING_MIN = 16;
    /** Outer radius of the candidate ring (blocks). */
    private static final int RING_MAX = 24;
    /** Compass directions sampled around the away-vector (every 30°); each is kept only if inside the away cone. */
    private static final int DIR_SAMPLES = 12;
    /** A sampled direction is in the "away" cone when its dot with the away-vector is ≥ this (≈ ±72°). */
    private static final double CONE_DOT = 0.30;
    /** Vertical band (± blocks around the body's feet Y) searched for a standable cell under each sampled point. */
    private static final int VERTICAL_PROBE = 4;
    /** Flee toward the owner instead only when the owner is this close AND sits opposite the threats. */
    private static final double OWNER_BIAS_RANGE = 64.0;
    /** Hostiles are gathered over this radius to build the threat centroid / influence field. */
    private static final double THREAT_GATHER_RADIUS = 20.0;
    /** Reflex flee runs at a sprint-ish nav speed. */
    private static final double NAV_SPEED = 1.0;
    /** Arrived when the feet are within this horizontal distance of the chosen cell. */
    private static final double ARRIVE_DIST = 2.5;

    private final NumenPlayer body;
    /** Ranked candidate cells (best first); index {@link #cursor} is the one currently being navigated. */
    private final List<BlockPos> candidates;
    private int cursor = -1;
    private PlayerNav nav;
    private BlockPos activeTarget;

    private ReflexNav(NumenPlayer body, List<BlockPos> candidates) {
        this.body = body;
        this.candidates = candidates;
        advance();   // arm the first (best) candidate
    }

    /**
     * A flee session away from {@code threats} (the primary/attacker included even if it is a neutral outside the
     * Monster scan). Returns {@code null} when no standable escape cell exists at all — the caller then uses its
     * v1 straight-line dash directly.
     */
    public static ReflexNav flee(NumenPlayer body, Entity primary) {
        if (!(body.level() instanceof ServerLevel level)) return null;
        List<Vec3> threats = gatherThreats(body, level, primary);
        if (threats.isEmpty()) return null;
        List<BlockPos> ranked = rankFleeCandidates(body, level, threats);
        return ranked.isEmpty() ? null : new ReflexNav(body, ranked);
    }

    /** Advance one tick of navigation, retrying the next-best candidate on an A* failure. */
    public Status tick() {
        if (nav == null) return Status.FAILED;
        return switch (nav.tick()) {
            case ARRIVED -> Status.ARRIVED;
            case RUNNING -> Status.RUNNING;
            case FAILED -> advance() ? Status.RUNNING : Status.FAILED;   // next candidate, or give up
        };
    }

    /** The cell currently being fled to (diagnostics / the event blurb); null before the first candidate. */
    public BlockPos target() {
        return activeTarget;
    }

    /** Stop navigating and zero the inputs — no residue (mirrors {@link PlayerNav#stop()}). */
    public void halt() {
        if (nav != null) {
            nav.stop();
            nav = null;
        }
    }

    /** Arm the next candidate as a fresh {@link PlayerNav}; false when the list is exhausted. */
    private boolean advance() {
        if (nav != null) { nav.stop(); nav = null; }
        cursor++;
        if (cursor >= candidates.size()) return false;
        activeTarget = candidates.get(cursor);
        Vec3 center = Vec3.atCenterOf(activeTarget);
        nav = new PlayerNav(body, activeTarget, NAV_SPEED,
                () -> horizontalDistSqr(body, center) <= ARRIVE_DIST * ARRIVE_DIST);
        return true;
    }

    // ============================================================ candidate selection (mostly pure)

    /** Positions of the hostiles that shape the flee — the Monster scan plus the recorded attacker (any neutral). */
    private static List<Vec3> gatherThreats(NumenPlayer body, ServerLevel level, Entity primary) {
        List<Vec3> out = new ArrayList<>();
        AABB box = body.getBoundingBox().inflate(THREAT_GATHER_RADIUS);
        for (Monster m : level.getEntitiesOfClass(Monster.class, box)) {
            if (m.isAlive()) out.add(m.position());
        }
        if (primary instanceof LivingEntity le && le.isAlive() && primary.level() == body.level()) {
            Vec3 p = primary.position();
            boolean dup = false;
            for (Vec3 q : out) if (q.distanceToSqr(p) < 1.0e-4) { dup = true; break; }
            if (!dup) out.add(p);
        }
        return out;
    }

    /**
     * Rank standable escape cells best-first: sample directions in the away-cone, drop a candidate at both ring
     * radii per direction, keep the standable ones, and sort by ascending threat influence (least-threatened
     * first). Owner-biased when the owner is near and on the far side of the threats.
     */
    private static List<BlockPos> rankFleeCandidates(NumenPlayer body, ServerLevel level, List<Vec3> threats) {
        Vec3 bodyPos = body.position();
        Vec3 away = awayDirection(bodyPos, threats);
        away = applyOwnerBias(body, bodyPos, threats, away);

        List<BlockPos> found = new ArrayList<>();
        for (int i = 0; i < DIR_SAMPLES; i++) {
            double ang = (2.0 * Math.PI * i) / DIR_SAMPLES;
            Vec3 dir = new Vec3(Math.cos(ang), 0.0, Math.sin(ang));
            if (dir.dot(away) < CONE_DOT) continue;   // outside the "away" cone — skip
            for (int radius = RING_MAX; radius >= RING_MIN; radius -= (RING_MAX - RING_MIN)) {
                int px = (int) Math.round(bodyPos.x + dir.x * radius);
                int pz = (int) Math.round(bodyPos.z + dir.z * radius);
                BlockPos cell = standableNear(level, px, body.blockPosition().getY(), pz);
                if (cell != null) found.add(cell);
                if (RING_MAX == RING_MIN) break;
            }
        }
        // Sort least-threatened first (ascending influence). Stable + pure: the influence field is fixed here.
        found.sort((a, b) -> Double.compare(
                influence(Vec3.atCenterOf(a), threats), influence(Vec3.atCenterOf(b), threats)));
        return found;
    }

    /** Unit horizontal vector pointing away from the threat centroid; a stable fallback if it degenerates. */
    static Vec3 awayDirection(Vec3 bodyPos, List<Vec3> threats) {
        double cx = 0.0, cz = 0.0;
        for (Vec3 t : threats) { cx += t.x; cz += t.z; }
        cx /= threats.size();
        cz /= threats.size();
        Vec3 away = new Vec3(bodyPos.x - cx, 0.0, bodyPos.z - cz);
        double len = away.length();
        return len < 1.0e-4 ? new Vec3(1.0, 0.0, 0.0) : away.scale(1.0 / len);
    }

    /** Flee toward the owner instead when the owner is online, near, and sits opposite the threats (rule 4). */
    private static Vec3 applyOwnerBias(NumenPlayer body, Vec3 bodyPos, List<Vec3> threats, Vec3 away) {
        ServerPlayer owner = body.resolveOwnerPlayer();
        if (owner == null || owner.level() != body.level() || body.distanceTo(owner) > OWNER_BIAS_RANGE) {
            return away;
        }
        Vec3 toOwner = new Vec3(owner.getX() - bodyPos.x, 0.0, owner.getZ() - bodyPos.z);
        double len = toOwner.length();
        if (len < 1.0e-4) return away;
        toOwner = toOwner.scale(1.0 / len);
        return toOwner.dot(away) > 0.0 ? toOwner : away;   // owner on the away side → head to the owner
    }

    /**
     * Threat influence at {@code cell}: Σ 1/(d²+1) over the threats — high near hostiles, decaying with distance
     * (the influence-map idea). Pure arithmetic; the flee picks the cell that MINIMISES this. Package-visible so
     * the geometry can be asserted by a bare unit test.
     */
    static double influence(Vec3 cell, List<Vec3> threats) {
        double sum = 0.0;
        for (Vec3 t : threats) {
            double dx = cell.x - t.x, dz = cell.z - t.z;
            sum += 1.0 / (dx * dx + dz * dz + 1.0);
        }
        return sum;
    }

    /** The nearest standable feet cell to the {@code (x, z)} column within ±{@value #VERTICAL_PROBE} of {@code y}. */
    private static BlockPos standableNear(ServerLevel level, int x, int y, int z) {
        for (int dy = 0; dy <= VERTICAL_PROBE; dy++) {
            BlockPos up = new BlockPos(x, y + dy, z);
            if (BlockHelper.isStandable(level, up)) return up;
            if (dy != 0) {
                BlockPos down = new BlockPos(x, y - dy, z);
                if (BlockHelper.isStandable(level, down)) return down;
            }
        }
        return null;
    }

    private static double horizontalDistSqr(NumenPlayer body, Vec3 center) {
        double dx = body.getX() - center.x, dz = body.getZ() - center.z;
        return dx * dx + dz * dz;
    }
}
