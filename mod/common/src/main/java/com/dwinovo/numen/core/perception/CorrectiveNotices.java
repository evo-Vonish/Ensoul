package com.dwinovo.numen.core.perception;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.entity.CompanionLifecycle;
import com.dwinovo.numen.entity.Companions;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;

/**
 * §8 <em>system-generated corrective notices</em> — the first batch of MECHANICAL
 * producers of {@code <system_notice>} for the {@code numen-core} tool pack
 * (see §8 of {@code docs/numen-context-design-v1.md}).
 *
 * <h2>Owner axiom — 凡承重,必机械</h2>
 * A correction is load-bearing, therefore it must be mechanical. Every notice here
 * is derived BY THE SYSTEM from an observable pattern (pure counters and
 * comparisons in the perception layer) — never from the model choosing to
 * self-report. Zero LLM involvement in <em>deciding</em> a notice should fire.
 *
 * <h2>§8 discipline — 重复本身就是信号;新 notice 追加,旧 notice 不改</h2>
 * The same problem recurring appends a NEW notice with escalated wording; earlier
 * notices are never modified. The prefix protocol resolves the coexistence of old
 * and new via "同主题以最高 seq 为准", and the engine stamps that {@code seq}
 * automatically at its choke point ({@code EntityAgentLoop.queueEventNote →
 * EventEnvelope.stamp}) — this class emits only the bare
 * {@code <system_notice provenance="system">…</system_notice>} tag. Every notice is
 * NON-URGENT: a correction is not an emergency, so it rides the next owner-driven
 * turn (a death notice rides the respawn turn) rather than preempting.
 *
 * <h2>Producers</h2>
 * <ul>
 *   <li><strong>Same-cause death stop-loss</strong> — {@link #onDeath}, driven by
 *       the engine's {@link CompanionLifecycle#onDeath} seam. The death count per
 *       normalized cause is durable ({@link DeathTolls}, keyed by companion name so
 *       it survives the respawn). 2nd death of a cause → a soft "change the plan"
 *       notice; 3rd+ → a hard "treat it as a no-go zone" escalation; 1st → silence
 *       (the ordinary death event already covers it).</li>
 *   <li><strong>Repeated-failure detector</strong> — {@link #onTaskResult}, driven
 *       from {@code CompanionTickDispatcher.drainResults} (every shipped server task
 *       result passes it). Three identical failures in a 600-tick streak → one
 *       "the method is wrong, change approach" notice, then a per-key 1200-tick
 *       cooldown so it doesn't spam every subsequent failure.</li>
 * </ul>
 *
 * <h2>Thread context</h2>
 * <strong>Server thread only.</strong> Both seams fire on the server tick thread;
 * session state lives on {@link PerceptionState} (the failure streaks) reached via
 * {@link Perceptions#stateFor}, and durable death counts on {@link DeathTolls} —
 * neither is synchronized, exactly like the rest of the perception layer.
 */
public final class CorrectiveNotices {

    // ---- repeated-failure detector ----
    /** Two identical failures more than this many ticks apart start a fresh streak (30s @ 20tps). */
    private static final int FAILURE_WINDOW_TICKS = 600;
    /** Nth identical failure in the streak that first trips the notice. */
    private static final int FAILURE_THRESHOLD = 3;
    /** Per-(tool, message-prefix) silence after a notice, so a persistent failure isn't reported every tick. */
    private static final int FAILURE_COOLDOWN_TICKS = 1200;
    /** How much of the failure message keys the streak — enough to be stable, short enough to aggregate. */
    private static final int FAILURE_PREFIX_CHARS = 40;

    private static boolean registered;

    private CorrectiveNotices() {}

    /**
     * Subscribe the death-driven producer. Called once from {@code NumenCore.init}
     * (a SECOND {@link CompanionLifecycle#onDeath} subscriber alongside the
     * existing {@code CompanionTickDispatcher::clearActiveTask}). The failure
     * producer needs no subscription — it is invoked directly from the task-result
     * drain point.
     */
    public static void register() {
        if (registered) return;
        registered = true;
        CompanionLifecycle.onDeath(CorrectiveNotices::onDeath);
        Constants.LOG.info("[numen-core] §8 system-generated corrective notices registered");
    }

    // ============================================================ death stop-loss

    /**
     * Same-cause death stop-loss. Runs inside the engine's {@code fireDeath(body)}
     * (see {@code Companions.onDeath}: line 103 reads the death message, line 105
     * fires this seam, line 115 defers the corpse despawn to after the tick) — so
     * the combat tracker is still intact here and {@code resolveOwnerPlayer()} still
     * resolves. We read the SAME {@code getCombatTracker().getDeathMessage()} the
     * engine reads, normalize it to strip the companion's name, and count it.
     */
    static void onDeath(NumenPlayer body) {
        try {
            // A creative companion can only die by /kill (invulnerability bypass), never from a survival hazard,
            // so it carries no survival-pattern to stop-loss against — don't count it or pollute the cause tolls.
            if (Perceptions.isCreative(body)) return;
            String name = body.getName().getString();
            String cause = normalizeCause(body.getCombatTracker().getDeathMessage().getString(), name);
            int n = DeathTolls.record(name, cause);
            if (n <= 1) return;   // 1st death of this cause: the ordinary death event already covers it.

            String safeCause = Perceptions.safe(cause);   // neutralize XML delimiters in a custom-mob cause
            if (n == 2) {
                emit(body, "这是你第 2 次死于" + safeCause + "。同一方法再试一次大概率同样失败——"
                        + "先改变方案(更好的装备/不同战术/干脆回避),不要立即重试。");
            } else {
                emit(body, "你已第 " + n + " 次死于" + safeCause + "。停止一切与之交战的尝试。"
                        + "这不是建议:在获得明显更强的装备或主人明确指示之前,视 " + safeCause + " 为禁区。");
            }
        } catch (Throwable t) {
            // A corrective note must never destabilize the death path.
            Constants.LOG.error("[numen-core] corrective death-notice failed for {}", body.getUUID(), t);
        }
    }

    /**
     * Strip the companion's name from a raw death message so differently-phrased
     * instances of the same death aggregate: {@code "Fenn被铁傀儡杀死了" →
     * "被铁傀儡杀死"}. Name removal is a literal (non-regex) replace of every
     * occurrence; a trailing {@code 了} is trimmed for a natural {@code 死于…}
     * reading. Blank / emptied results fall back to the engine's own "未知原因".
     */
    static String normalizeCause(String raw, String name) {
        if (raw == null || raw.isBlank()) return "未知原因";
        String c = raw.strip();
        if (name != null && !name.isEmpty()) c = c.replace(name, "");
        c = c.strip();
        if (c.endsWith("了")) c = c.substring(0, c.length() - 1).strip();
        return c.isEmpty() ? "未知原因" : c;
    }

    // ======================================================= repeated-failure detector

    /**
     * Watch one shipped server task result for a repeated-failure pattern. Called
     * from {@code CompanionTickDispatcher.drainResults} for every completed record
     * (client-local QUERY tools never reach that path, so they are out of scope by
     * construction). Owner-cancellations are excluded — the owner interrupting is
     * not a method failure; a plain failure or a timeout is.
     *
     * @param body     the companion whose task just completed
     * @param toolName the originating tool ({@code TaskRecord.getToolName()})
     * @param result   the typed outcome, or {@code null} when none was produced
     *                 (treated as a failure, matching the drain's own fallback JSON)
     */
    public static void onTaskResult(NumenPlayer body, String toolName, TaskResult result) {
        try {
            if (result != null && result.interrupted()) return;   // owner Stop — not a method problem
            boolean failed = (result == null) || !result.success();
            if (!failed) return;

            String message = result == null ? "no result produced"
                    : (result.message() == null ? "" : result.message());
            String prefix = message.strip();
            if (prefix.length() > FAILURE_PREFIX_CHARS) prefix = prefix.substring(0, FAILURE_PREFIX_CHARS);
            String key = toolName + "|" + prefix;

            long now = body.level().getGameTime();
            PerceptionState st = Perceptions.stateFor(body);

            // Consecutive-failure streak: reset when the gap since the last identical failure
            // exceeds the window; otherwise extend it. Element 0 = last-failure tick, 1 = count.
            long[] streak = st.failureStreaks.get(key);
            long count;
            if (streak == null || now - streak[0] > FAILURE_WINDOW_TICKS) {
                st.failureStreaks.put(key, new long[]{now, 1L});
                count = 1L;
            } else {
                streak[0] = now;
                streak[1]++;
                count = streak[1];
            }

            if (count >= FAILURE_THRESHOLD && gate(st, "failure", key, now, FAILURE_COOLDOWN_TICKS)) {
                emit(body, "同一操作已连续失败 " + count + " 次(" + Perceptions.safe(toolName)
                        + ": " + Perceptions.safe(prefix) + "…)。方法有问题——换一种途径,不要原样重试。");
            }
        } catch (Throwable t) {
            Constants.LOG.error("[numen-core] corrective failure-notice failed for {}", body.getUUID(), t);
        }
    }

    // ================================================================== emit / gate

    /**
     * The one emission helper (alongside {@code Perceptions.send}): wrap the inner text in
     * {@code <system_notice provenance="system">…</system_notice>} and push it to the brain
     * NON-URGENT — corrections ride the next owner turn, never preempt. The engine stamps
     * {@code seq}/{@code schemaVersion}/{@code gameTime} onto the tag automatically.
     */
    private static void emit(NumenPlayer body, String inner) {
        Companions.emitEvent(body, "<system_notice provenance=\"system\">" + inner + "</system_notice>", false);
    }

    /**
     * Per-(kind, subject) rate limit, reusing {@link PerceptionState}'s cooldown map (the same
     * clocks {@code Perceptions} gates proximity on): returns true and arms the cooldown when the
     * subject may emit, false while it is still cooling down.
     */
    private static boolean gate(PerceptionState st, String kind, String subject, long now, int cooldownTicks) {
        String key = kind + "|" + subject;
        if (!st.ready(key, now)) return false;
        st.arm(key, now, cooldownTicks);
        return true;
    }
}
