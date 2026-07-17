package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.task.TurtleUpTaskRecord;
import com.dwinovo.numen.core.tool.Schema;
import com.dwinovo.numen.core.tool.ServerNumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Muscle tool (raw {@link ServerNumenTool}): turtle up — burrow a shallow shaft straight down, cap it overhead,
 * and hunker down until the coast is clear. The brain-orderable face of the {@code TurtleDrive} muscle the reflex
 * layer also fires automatically when the engagement engine says a fight is un-winnable and flight is impossible
 * ({@code CORNERED}). No arguments, no pathfinder search.
 */
public final class TurtleUpTool extends ServerNumenTool {

    private static final long BASE_TIMEOUT_TICKS = 5 * 60 * 20;

    @Override
    public String name() {
        return "turtle_up";
    }

    @Override
    public String description() {
        return "龟缩自保:在原地向下挖 2~3 格竖坑,再用背包里的方块把头顶封住,缩进掩体里躲过威胁。这是"
                + "「既打不过又跑不掉」时的最后一招(交战评估为 CORNERED / 被围困):打不过、逃不掉,那就把自己"
                + "埋起来等它散去。一次调用内部确定性闭环完成,不做寻路搜索——徒手能挖的泥土/沙/砂砾直接挖,"
                + "石头需要镐;没有可封顶的方块就只蹲坑(仍能挡住大半近战)。封顶后每秒复查一次,威胁散去/天亮/"
                + "主人靠近时会自动破土而出。注意:苦力怕逼近时不要龟缩(爆炸会掀开掩体)——那种情况要撤离。无参数。";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.none();
    }

    @Override
    public void runOnServer(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        enqueue(companion, new TurtleUpTaskRecord(toolCallId,
                ctx(toolCallId, companion).deadline(BASE_TIMEOUT_TICKS)));
    }
}
