package com.dwinovo.numen.core.task;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.exec.InputDriver;
import com.dwinovo.numen.core.task.CompanionTask;
import com.dwinovo.numen.task.TaskResult;
import com.dwinovo.numen.core.task.TaskState;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code wait} on the player body: idle in place for the requested duration
 * (game-time based, freeze/tick-rate aware). Player-body twin of WaitTaskGoal.
 */
public final class WaitCompanionTask implements CompanionTask {

    private final NumenPlayer player;
    private final WaitTaskRecord r;
    private long wakeAtGameTime;

    public WaitCompanionTask(NumenPlayer player, WaitTaskRecord record) {
        this.player = player;
        this.r = record;
    }

    @Override
    public void start() {
        this.wakeAtGameTime = player.level().getGameTime() + r.seconds * 20L;
        InputDriver.halt(player);
    }

    @Override
    public TaskState tick() {
        InputDriver.halt(player);   // hold still while idling
        return player.level().getGameTime() >= wakeAtGameTime ? TaskState.SUCCESS : TaskState.RUNNING;
    }

    @Override
    public TaskResult buildResult(TaskState finalState) {
        Map<String, Object> data = new HashMap<>();
        data.put("seconds", r.seconds);
        String label = r.seconds + "s" + (r.reason.isEmpty() ? "" : " (" + r.reason + ")");
        return switch (finalState) {
            case SUCCESS -> TaskResult.ok("waited " + label, data);
            case CANCELLED -> {
                data.put("elapsed_seconds", elapsedSeconds());
                yield TaskResult.cancelled("wait 被打断 —— " + progressSummary(), data);
            }
            case TIMEOUT -> {
                data.put("elapsed_seconds", elapsedSeconds());
                yield TaskResult.timeout("wait 超时 —— " + progressSummary(), data);
            }
            default -> TaskResult.fail("unexpected state: " + finalState);
        };
    }

    /** 已等的整秒数。start() 未跑过(wakeAt 仍为 0)时安全返回 0。 */
    private long elapsedSeconds() {
        if (wakeAtGameTime <= 0) return 0;
        long remain = Math.max(0, (wakeAtGameTime - player.level().getGameTime()) / 20);
        return Math.max(0, Math.min(r.seconds, r.seconds - remain));
    }

    /** 结算时刻的实时进度:已等多久、目标多久。 */
    @Override
    public String progressSummary() {
        return "已等 " + elapsedSeconds() + "/" + r.seconds + "s"
                + (r.reason.isEmpty() ? "" : "(" + r.reason + ")");
    }
}
