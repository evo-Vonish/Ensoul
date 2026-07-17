package com.dwinovo.numen.core.task;

/**
 * Typed task descriptor for the {@code pillar_up} muscle: rise {@code height} blocks
 * straight up in place by pillaring (jump + place a scaffold block underfoot, digging
 * any head-room block first). Deterministic — no pathfinder search.
 */
public final class PillarUpTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "pillar_up";

    /** How many blocks to rise (already clamped to a sane range by the tool). */
    public final int height;

    public PillarUpTaskRecord(String toolCallId, long deadlineGameTime, int height) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.height = height;
    }

    @Override
    public String describe() {
        return TOOL_NAME + " " + height;
    }
}
