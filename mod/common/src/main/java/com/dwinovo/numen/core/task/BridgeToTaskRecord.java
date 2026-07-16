package com.dwinovo.numen.core.task;

import net.minecraft.core.BlockPos;

/**
 * Typed task descriptor for the {@code bridge_to} muscle: reach the exact cell
 * {@code target} by bridging — placing a floor of scaffold blocks across gaps and
 * up gentle slopes as needed. Wraps the pathfinder's proven BRIDGE execution
 * (traverse-with-place) toward one exact goal cell, with honest arrival reporting.
 */
public final class BridgeToTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "bridge_to";

    public final BlockPos target;

    public BridgeToTaskRecord(String toolCallId, long deadlineGameTime, BlockPos target) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.target = target;
    }

    @Override
    public String describe() {
        return TOOL_NAME + " " + target.getX() + "," + target.getY() + "," + target.getZ();
    }
}
