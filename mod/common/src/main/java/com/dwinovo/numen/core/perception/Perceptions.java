package com.dwinovo.numen.core.perception;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.perception.region.RegionCognition;
import com.dwinovo.numen.core.perception.region.RegionKey;
import com.dwinovo.numen.core.perception.region.RegionStore;
import com.dwinovo.numen.entity.CompanionLifecycle;
import com.dwinovo.numen.entity.Companions;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.entity.PerceptionEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * L3 immediate-perception policy for the {@code numen-core} tool pack — Wave B of
 * the append-only world-cognition design ({@code docs/append-only-world-cognition-v1.md}).
 *
 * <p>The engine ({@code numen-api}) owns the companion body and mechanically
 * relays raw occurrences through two channels; this class owns what they
 * <em>mean</em>:
 * <ul>
 *   <li><strong>seams</strong> — {@link PerceptionEvents} fires per raw hurt /
 *       effect change / item pickup; we batch, rate-limit and phrase them;</li>
 *   <li><strong>poll</strong> — {@link #tick(MinecraftServer)} runs each server
 *       tick (registered from both loaders next to {@code ScanBlocksJob::tick})
 *       and edge-detects state that has no event: hunger, tool durability,
 *       hostile proximity, time-of-day / weather.</li>
 * </ul>
 *
 * <p>Every emission is a Chinese {@code <event kind="...">} note pushed to the
 * companion's brain via {@link Companions#emitEvent} — {@code urgent} wakes an
 * idle brain now, otherwise it rides the next owner-driven turn. All wording and
 * formatting funnel through {@link #send}; all per-subject rate-limiting funnels
 * through the cooldown map on {@link PerceptionState}. Server-thread only, and
 * throttled so a tick with no live companion (or no due poll) costs almost
 * nothing. Immediate perception is short-lived tail-only: it never enters the
 * landmark map or the disk event log (L1/L2 are out of scope here).
 */
public final class Perceptions {

    // ---- hurt ----
    private static final int HURT_WINDOW_TICKS = 40;   // merge a burst of hits into one follow-up
    private static final float HP_QUARTER = 0.25f;     // crossing below this re-arms an urgent alert

    // ---- item pickup ----
    private static final int PICKUP_BATCH_TICKS = 100; // aggregate pickups over this window

    // ---- hunger (poll divisor 10) ----
    private static final int HUNGER_LOW = 6;           // ≤6 → non-urgent
    private static final int HUNGER_CRIT = 2;          // ≤2 → urgent
    private static final int HUNGER_RECOVER = 10;      // re-arm only after recovering above this

    // ---- tool durability (poll divisor 10, mainhand only) ----
    private static final float DURABILITY_LOW_FRAC = 0.10f;  // warn under 10% remaining

    // ---- hostile proximity (poll divisor 20) ----
    private static final double CREEPER_RADIUS = 8.0;  // creeper within this → urgent
    private static final double MONSTER_RADIUS = 5.0;  // any other monster within this → non-urgent
    private static final int CREEPER_COOLDOWN = 200;   // per-entity
    private static final int MONSTER_COOLDOWN = 200;   // per-type

    // ---- regional observation (L2, Wave C) ----
    private static final int POLL_REGION = 20;         // check for a region-boundary crossing
    private static final int REGION_COOLDOWN = 200;    // per-region re-observation cooldown

    // ---- poll divisors ----
    private static final int POLL_HUNGER_DURABILITY = 10;
    private static final int POLL_PROXIMITY = 20;
    private static final int POLL_TIME_WEATHER = 100;

    /** Night band of the standard 24000-tick day cycle: [13000, 23000). */
    private static final long NIGHT_START = 13000L;
    private static final long NIGHT_END = 23000L;
    private static final long DAY_LENGTH = 24000L;

    /** Per-companion state, keyed by UUID. Server-thread only (mirrors CompanionTickDispatcher). */
    static final Map<UUID, PerceptionState> STATES = new HashMap<>();

    private static boolean registered;

    private Perceptions() {}

    /**
     * Subscribe the perception seams and the lifecycle cleanup. Called once from
     * {@code NumenCore.init}; the poll pass ({@link #tick}) is registered
     * separately by each loader entry point.
     */
    public static void register() {
        if (registered) return;
        registered = true;
        PerceptionEvents.onHurt(Perceptions::onHurt);
        PerceptionEvents.onEffectChange(Perceptions::onEffectChange);
        PerceptionEvents.onItemPickup(Perceptions::onItemPickup);
        // Drop per-companion perception + region-memory state when the body leaves the world.
        CompanionLifecycle.onRemove(body -> {
            STATES.remove(body.getUUID());
            RegionStore.drop(body.getUUID());
        });
        Constants.LOG.info("[numen-core] L3 immediate perception + L2 regional observation registered");
    }

    private static PerceptionState stateFor(NumenPlayer body) {
        return STATES.computeIfAbsent(body.getUUID(), k -> new PerceptionState());
    }

    // ================================================================= poll pass

    /** Server-tick poll of every live companion. Cheap when there are none due. */
    public static void tick(MinecraftServer server) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) return;
        for (ServerPlayer p : players) {
            if (!(p instanceof NumenPlayer body)) continue;
            try {
                tickOne(body);
            } catch (Throwable t) {
                Constants.LOG.error("[numen-core] perception tick failed for {}", body.getUUID(), t);
            }
        }
    }

    private static void tickOne(NumenPlayer body) {
        PerceptionState st = stateFor(body);
        long now = body.level().getGameTime();

        // Time-independent flushes (O(1) guards; only work when something is buffered).
        if (st.hurtWindowEnd != 0 && now >= st.hurtWindowEnd) flushHurt(body, st);
        if (st.pickupFlushAt != 0 && now >= st.pickupFlushAt) flushPickups(body, st);

        // Throttled polls.
        if (now % POLL_HUNGER_DURABILITY == 0) {
            pollHunger(body, st);
            pollDurability(body, st);
        }
        if (now % POLL_PROXIMITY == 0) {
            pollProximity(body, st, now);
        }
        if (now % POLL_TIME_WEATHER == 0) {
            pollTimeAndWeather(body, st);
        }
        if (now % POLL_REGION == 0) {
            pollRegion(body, st, now);
        }
    }

    // ============================================== regional observation (poll)

    /**
     * L2 driver: on a region-boundary crossing (edge-trigger), observe the region just entered —
     * subject to a per-region cooldown so pacing the boundary can't re-scan. The observation and
     * its append-only snapshot/diff/checkpoint logic live in {@link RegionCognition}.
     */
    private static void pollRegion(NumenPlayer body, PerceptionState st, long now) {
        if (!(body.level() instanceof ServerLevel level)) return;
        RegionKey current = RegionKey.of(level.dimension().identifier().toString(), body.blockPosition());
        String key = current.storageKey();
        if (key.equals(st.lastRegionKey)) return;      // still in the same region — no crossing
        st.lastRegionKey = key;
        if (gate(st, "region", key, now, REGION_COOLDOWN)) {
            RegionCognition.observe(body, level, current, now);
        }
    }

    // ================================================================= hurt (seam)

    private static void onHurt(PerceptionEvents.HurtInfo info) {
        NumenPlayer body = info.body();
        PerceptionState st = stateFor(body);
        long now = body.level().getGameTime();
        float hpAfter = info.remainingHealth();
        float maxHp = body.getMaxHealth();
        float quarter = maxHp * HP_QUARTER;
        String attacker = attackerLabel(info.source());
        boolean windowOpen = st.hurtWindowEnd != 0 && now < st.hurtWindowEnd;

        if (!windowOpen) {
            // First hit of a fresh window → urgent alert.
            st.hurtWindowEnd = now + HURT_WINDOW_TICKS;
            st.hurtCount = 1;
            st.hurtHpFrom = hpAfter;
            st.hurtHpTo = hpAfter;
            st.hurtAttacker = attacker;
            // If already below a quarter, the first alert covers it — don't double-fire on merge.
            st.hurtQuarterFired = hpAfter <= quarter;
            send(body, "hurt", firstHurtText(info, attacker, body), true);
        } else {
            // Subsequent hits merge; only the crossing below 25% breaks through as a new urgent.
            st.hurtCount++;
            st.hurtHpTo = hpAfter;
            if (st.hurtAttacker == null && attacker != null) st.hurtAttacker = attacker;
            if (!st.hurtQuarterFired && hpAfter > 0.0f && hpAfter <= quarter) {
                st.hurtQuarterFired = true;
                send(body, "hurt", "警告:你的生命值已降到 " + fmt(hpAfter) + "/" + fmt(maxHp)
                        + "(不足 25%),攻击者 " + (st.hurtAttacker != null ? st.hurtAttacker : "未知来源")
                        + ",情况危急,立即撤退或反击!", true);
            }
        }
    }

    /** Window closed: if more than the first hit landed, emit ONE merged non-urgent summary. */
    private static void flushHurt(NumenPlayer body, PerceptionState st) {
        if (st.hurtCount > 1) {
            String attacker = st.hurtAttacker != null ? st.hurtAttacker : "未知来源";
            send(body, "hurt", "你在持续受到攻击:" + attacker + " 命中 " + st.hurtCount
                    + " 次,生命值 " + fmt(st.hurtHpFrom) + " → " + fmt(st.hurtHpTo) + "。", false);
        }
        st.hurtWindowEnd = 0;
        st.hurtCount = 0;
        st.hurtAttacker = null;
        st.hurtQuarterFired = false;
    }

    private static String firstHurtText(PerceptionEvents.HurtInfo info, String attacker, NumenPlayer body) {
        StringBuilder sb = new StringBuilder("你受到攻击!");
        Entity e = attackerEntity(info.source());
        if (attacker != null) {
            sb.append("来自 ").append(attacker);
            if (e != null) {
                int dx = (int) Math.round(e.getX() - body.getX());
                int dz = (int) Math.round(e.getZ() - body.getZ());
                sb.append("(").append(dirCn(dx, dz)).append("方向约 ")
                        .append((int) Math.round(body.distanceTo(e))).append(" 格)");
            }
        } else {
            sb.append("来源不明(").append(safe(info.source().getMsgId())).append(")");
        }
        sb.append(",受到伤害 ").append(fmt(info.amount()))
                .append(",剩余生命值 ").append(fmt(info.remainingHealth()))
                .append("/").append(fmt(body.getMaxHealth()));
        if (info.blocking()) sb.append(",你正在格挡");
        sb.append("。");
        return sb.toString();
    }

    private static String attackerLabel(DamageSource source) {
        Entity e = attackerEntity(source);
        return e == null ? null : safe(e.getName().getString());
    }

    /** The causing entity if any, falling back to the direct entity (e.g. an arrow's shooter/arrow). */
    private static Entity attackerEntity(DamageSource source) {
        Entity e = source.getEntity();
        return e != null ? e : source.getDirectEntity();
    }

    // ============================================================ effects (seam)

    private static void onEffectChange(PerceptionEvents.EffectInfo info) {
        NumenPlayer body = info.body();
        MobEffectInstance eff = info.effect();
        String name = safe(eff.getEffect().value().getDisplayName().getString());
        if (info.added()) {
            int amp = eff.getAmplifier();
            int dur = eff.getDuration();
            String level = amp > 0 ? (" " + (amp + 1) + " 级") : "";
            String duration = dur < 0 ? "无限持续" : ("持续约 " + (dur / 20) + " 秒");
            send(body, "status_effect", "你获得了状态效果:" + name + level + "," + duration + "。", false);
        } else {
            send(body, "status_effect", "状态效果结束:" + name + "。", false);
        }
    }

    // ============================================================= pickup (seam)

    private static void onItemPickup(PerceptionEvents.PickupInfo info) {
        NumenPlayer body = info.body();
        PerceptionState st = stateFor(body);
        long now = body.level().getGameTime();
        String name = safe(info.stack().getHoverName().getString());
        st.pickupBatch.merge(name, info.count(), Integer::sum);
        if (st.pickupFlushAt == 0) st.pickupFlushAt = now + PICKUP_BATCH_TICKS;
    }

    private static void flushPickups(NumenPlayer body, PerceptionState st) {
        if (!st.pickupBatch.isEmpty()) {
            StringBuilder sb = new StringBuilder("你捡起了:");
            boolean first = true;
            for (Map.Entry<String, Integer> e : st.pickupBatch.entrySet()) {
                if (!first) sb.append("、");
                sb.append(e.getKey()).append("×").append(e.getValue());
                first = false;
            }
            sb.append("。");
            send(body, "item_pickup", sb.toString(), false);
            st.pickupBatch.clear();
        }
        st.pickupFlushAt = 0;
    }

    // ============================================================= hunger (poll)

    private static void pollHunger(NumenPlayer body, PerceptionState st) {
        int hunger = body.getFoodData().getFoodLevel();
        st.lastHunger = hunger;
        if (hunger > HUNGER_RECOVER) {   // recovered — re-arm both edges
            st.hungerLowFired = false;
            st.hungerCritFired = false;
            return;
        }
        if (hunger <= HUNGER_CRIT && !st.hungerCritFired) {
            st.hungerCritFired = true;
            st.hungerLowFired = true;    // crit subsumes the low note
            send(body, "hunger", "你非常饥饿(饥饿值 " + hunger
                    + "/20),急需进食,否则将开始受到饥饿伤害。", true);
        } else if (hunger <= HUNGER_LOW && !st.hungerLowFired) {
            st.hungerLowFired = true;
            send(body, "hunger", "你饿了(饥饿值 " + hunger + "/20),找机会吃点东西。", false);
        }
    }

    // ========================================================= durability (poll)

    private static void pollDurability(NumenPlayer body, PerceptionState st) {
        ItemStack hand = body.getMainHandItem();
        boolean empty = hand.isEmpty();
        boolean damageable = !empty && hand.isDamageableItem();
        String key = empty ? "" : itemId(hand);
        int remaining = damageable ? (hand.getMaxDamage() - hand.getDamageValue()) : -1;

        // Break: last poll the mainhand was damageable with ≤1 durability left, and now that exact
        // item is gone from the slot (emptied or swapped for something else).
        if (st.lastMainhandDamageable && st.lastMainhandRemaining >= 0 && st.lastMainhandRemaining <= 1
                && (empty || !key.equals(st.lastMainhandKey))) {
            send(body, "tool_broke", "你的" + st.lastMainhandName + "用坏了。", true);
        }

        // Low durability: warn once per item, re-armed when it rises back above the threshold.
        if (damageable && hand.getMaxDamage() > 0) {
            float frac = (float) remaining / hand.getMaxDamage();
            if (frac < DURABILITY_LOW_FRAC) {
                if (!key.equals(st.durabilityWarnedKey)) {
                    st.durabilityWarnedKey = key;
                    send(body, "tool_durability", "你手中的" + safe(hand.getHoverName().getString())
                            + "快用坏了(剩余耐久约 " + Math.round(frac * 100) + "%),尽快更换或修复。", false);
                }
            } else if (key.equals(st.durabilityWarnedKey)) {
                st.durabilityWarnedKey = null;   // healthy again — allow a future warning
            }
        } else if (st.durabilityWarnedKey != null && !st.durabilityWarnedKey.equals(key)) {
            st.durabilityWarnedKey = null;       // switched to a non-durable/empty hand
        }

        st.lastMainhandKey = key;
        st.lastMainhandName = empty ? "" : safe(hand.getHoverName().getString());
        st.lastMainhandRemaining = remaining;
        st.lastMainhandDamageable = damageable;
    }

    // ========================================================== proximity (poll)

    private static void pollProximity(NumenPlayer body, PerceptionState st, long now) {
        AABB box = body.getBoundingBox().inflate(CREEPER_RADIUS);
        List<Entity> near = body.level().getEntities(body, box);
        if (near.isEmpty()) return;   // silent on peaceful: no monsters spawn / persist
        for (Entity e : near) {
            if (e instanceof Creeper) {
                double d = body.distanceTo(e);
                if (d <= CREEPER_RADIUS && gate(st, "proximity", "creeper:" + e.getId(), now, CREEPER_COOLDOWN)) {
                    int dx = (int) Math.round(e.getX() - body.getX());
                    int dz = (int) Math.round(e.getZ() - body.getZ());
                    send(body, "hostile_proximity", "警告:附近有苦力怕!" + dirCn(dx, dz)
                            + "方向约 " + (int) Math.round(d) + " 格,小心它靠近后爆炸。", true);
                }
            } else if (e instanceof Monster) {
                double d = body.distanceTo(e);
                if (d <= MONSTER_RADIUS
                        && gate(st, "proximity", "monster:" + typeId(e), now, MONSTER_COOLDOWN)) {
                    int dx = (int) Math.round(e.getX() - body.getX());
                    int dz = (int) Math.round(e.getZ() - body.getZ());
                    send(body, "hostile_proximity", "附近有敌对生物:" + safe(e.getName().getString())
                            + "(" + dirCn(dx, dz) + "方向约 " + (int) Math.round(d) + " 格)。", false);
                }
            }
        }
    }

    // ====================================================== time / weather (poll)

    private static void pollTimeAndWeather(NumenPlayer body, PerceptionState st) {
        Level level = body.level();

        // Day/night edges only where a cycle actually exists (skip the nether/end's fixed time).
        if (!level.dimensionType().hasFixedTime()) {
            long clock = Math.floorMod(level.getOverworldClockTime(), DAY_LENGTH);
            int phase = (clock >= NIGHT_START && clock < NIGHT_END) ? 1 : 0;
            if (st.lastTimePhase == -1) {
                st.lastTimePhase = phase;   // first observation — record, don't announce
            } else if (phase != st.lastTimePhase) {
                st.lastTimePhase = phase;
                if (phase == 1) {
                    send(body, "time_of_day", "夜幕降临了,怪物会开始在黑暗中出现,注意安全。", false);
                } else {
                    send(body, "time_of_day", "天亮了,新的一天开始。", false);
                }
            }
        }

        // Thunderstorm start (independent of dimension time).
        boolean thundering = level.isThundering();
        if (!st.weatherInit) {
            st.weatherInit = true;
        } else if (thundering && !st.lastThundering) {
            send(body, "weather", "开始打雷了,进入雷暴天气,注意落雷,部分怪物会变得更强。", false);
        }
        st.lastThundering = thundering;
    }

    // ================================================================== helpers

    /**
     * The one emission helper: format the Chinese {@code <event kind="...">} and push it to the
     * companion's brain. {@code urgent} wakes an idle brain now; otherwise it rides the next turn.
     */
    private static void send(NumenPlayer body, String kind, String inner, boolean urgent) {
        Companions.emitEvent(body, "<event kind=\"" + kind + "\">" + inner + "</event>", urgent);
    }

    /**
     * Per-(kind, subject) rate limit backed by {@link PerceptionState}'s cooldown map: returns true
     * and arms the cooldown when the subject is allowed to emit, false while it is still cooling down.
     */
    private static boolean gate(PerceptionState st, String kind, String subject, long now, int cooldownTicks) {
        String key = kind + "|" + subject;
        if (!st.ready(key, now)) return false;
        st.arm(key, now, cooldownTicks);
        return true;
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static String typeId(Entity e) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
    }

    /** 8-point Chinese compass from a block delta (+X 东 / east, +Z 南 / south). */
    private static String dirCn(int dx, int dz) {
        if (dx == 0 && dz == 0) return "附近";
        String ns = dz < 0 ? "北" : (dz > 0 ? "南" : "");
        String ew = dx < 0 ? "西" : (dx > 0 ? "东" : "");
        if (ns.isEmpty()) return ew;
        if (ew.isEmpty()) return ns;
        if (Math.abs(dz) >= 2 * Math.abs(dx)) return ns;
        if (Math.abs(dx) >= 2 * Math.abs(dz)) return ew;
        return ew + ns;   // 东北 / 东南 / 西北 / 西南
    }

    /** Trim a float to a compact string: "12" for whole values, "6.5" otherwise. */
    private static String fmt(float v) {
        return v == Math.rint(v) ? Integer.toString((int) v) : String.format(Locale.ROOT, "%.1f", v);
    }

    /** Neutralise the XML delimiters so a custom entity/item name can't break the {@code <event>} tag. */
    private static String safe(String s) {
        if (s == null) return "";
        return s.replace('&', '＆').replace('<', '（').replace('>', '）');
    }
}
