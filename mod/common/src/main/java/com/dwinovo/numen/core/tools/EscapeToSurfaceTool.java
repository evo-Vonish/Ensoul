package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.task.EscapeToSurfaceTaskRecord;
import com.dwinovo.numen.core.tool.Schema;
import com.dwinovo.numen.core.tool.ServerNumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Muscle tool (raw {@link ServerNumenTool}): climb a vertical shaft straight up to
 * the open-air surface. Wraps the deterministic {@code PillarDrive} climb with a
 * fluid/bedrock-ceiling sidestep — no arguments, no pathfinder search.
 */
public final class EscapeToSurfaceTool extends ServerNumenTool {

    private static final long BASE_TIMEOUT_TICKS = 90 * 20;   // task extends by depth at start

    @Override
    public String name() {
        return "escape_to_surface";
    }

    @Override
    public String description() {
        return "垂直脱困上地表:在原地打一口竖井直上——交替「挖头顶方块」和「脚下垫脚手架起跳」,一格一格"
                + "爬到露天地表(当前所在列的地表高度)。这是专为「被埋在洞底/矿道/坑里出不来」设计的一整块"
                + "「肌肉」:一次调用内部确定性闭环完成,不做寻路搜索(正是当初把同伴困在井底 110 轮的那种"
                + "仰角寻路会爆搜索预算的场景)。头顶若是岩浆或水绝不硬挖,会自动侧移一格换一列继续往上;"
                + "四周都无安全出口时停下并说明。有镐+脚手架基本必成。无参数。缺脚手架/被岩浆水基岩彻底"
                + "封死时会报告已经升到哪、离地表还差多少。困住时的首选自救。";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.none();
    }

    @Override
    public void runOnServer(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        enqueue(companion, new EscapeToSurfaceTaskRecord(toolCallId,
                ctx(toolCallId, companion).deadline(BASE_TIMEOUT_TICKS)));
    }
}
