package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.task.BridgeToTaskRecord;
import com.dwinovo.numen.core.tool.Schema;
import com.dwinovo.numen.core.tool.ServerNumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Muscle tool (raw {@link ServerNumenTool}): bridge across a gap to an exact cell,
 * laying a scaffold floor as it goes. Wraps the pathfinder's BRIDGE execution.
 */
public final class BridgeToTool extends ServerNumenTool {

    private static final Gson GSON = new Gson();
    private static final long BASE_TIMEOUT_TICKS = 60 * 20;   // task extends by distance at start

    private record Args(Integer x, Integer y, Integer z) {}

    @Override
    public String name() {
        return "bridge_to";
    }

    @Override
    public String description() {
        return "搭桥前往一个精确坐标:一路把脚下缺失的地面用脚手架方块补上(平桥,或沿缓坡的斜桥),"
                + "像真人一样探身贴边放块、再踏上去。这是一整块「肌肉」——跨越沟壑、悬空缺口或熔岩/水面"
                + "上方时,一次调用内部自动完成「放一格→走一格」的搭桥循环,不用你逐格 place_block。给出"
                + "目标格 (x,y,z)(通常是你想站上去的那一格)。需要背包里有脚手架方块(圆石、泥土之类);"
                + "被墙挡死或脚手架耗尽会停下并说明原因、报告实际停在哪、用了多少脚手架。跨越明确的空隙/"
                + "缺口用本工具;一般的地面赶路仍用 move_to。";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .integer("x", "目标格 x。")
                .integer("y", "目标格 y。")
                .integer("z", "目标格 z。")
                .build();
    }

    @Override
    public void runOnServer(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        if (a.x() == null || a.y() == null || a.z() == null) {
            throw new IllegalArgumentException("bridge_to needs all of x, y, z (the exact target cell).");
        }
        BlockPos target = new BlockPos(a.x(), a.y(), a.z());
        enqueue(companion, new BridgeToTaskRecord(toolCallId,
                ctx(toolCallId, companion).deadline(BASE_TIMEOUT_TICKS), target));
    }
}
