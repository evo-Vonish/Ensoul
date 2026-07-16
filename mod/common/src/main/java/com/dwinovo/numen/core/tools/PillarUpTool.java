package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.task.PillarUpTaskRecord;
import com.dwinovo.numen.core.tool.Schema;
import com.dwinovo.numen.core.tool.ServerNumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Muscle tool (raw {@link ServerNumenTool}): pillar straight up N blocks in place.
 * Wraps the deterministic {@code PillarDrive} climb — no pathfinder search.
 */
public final class PillarUpTool extends ServerNumenTool {

    private static final Gson GSON = new Gson();
    private static final long BASE_TIMEOUT_TICKS = 30 * 20;   // task extends by height at start
    private static final int MAX_HEIGHT = 64;

    private record Args(Integer height) {}

    @Override
    public String name() {
        return "pillar_up";
    }

    @Override
    public String description() {
        return "原地垫脚上升:在当前位置竖直向上垒 N 格并站上去(蹲下防滑、起跳、在脚下方块处放置脚手架,"
                + "如此一格一格上升)。这是一整块「肌肉」——一次调用内部自动闭环完成整套跳-放循环,不需要你"
                + "逐格 place_block。头顶若有方块会先挖掉再上升(自动切最合适的工具)。用于:爬出坑/洞、"
                + "登上够不到的高台、快速抬升视野。需要背包里有脚手架方块(圆石、泥土之类);缺料或头顶是"
                + "岩浆/水/基岩时会立刻停下并说清楚原因和已经升了几格。想去某个具体坐标用 move_to;只想"
                + "单纯往上时用本工具最省心。";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .integer("height", "要上升的格数(1-" + MAX_HEIGHT + ")。", 1, MAX_HEIGHT)
                .build();
    }

    @Override
    public void runOnServer(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        int height = a.height() == null ? 1 : Math.clamp(a.height(), 1, MAX_HEIGHT);
        enqueue(companion, new PillarUpTaskRecord(toolCallId,
                ctx(toolCallId, companion).deadline(BASE_TIMEOUT_TICKS), height));
    }
}
