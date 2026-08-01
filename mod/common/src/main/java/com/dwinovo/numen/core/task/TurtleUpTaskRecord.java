package com.dwinovo.numen.core.task;

/**
 * Typed task descriptor for the {@code turtle_up} muscle: burrow a shallow one-wide shaft straight down, cap it
 * overhead, and hunker down until the coast is clear. No arguments — the burrow is always at the body's own
 * column. The "can neither win nor flee" (CORNERED) escape hatch, orderable by the brain; the reflex layer fires
 * the same {@link com.dwinovo.numen.core.pathing.exec.TurtleDrive} muscle automatically.
 */
public final class TurtleUpTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "turtle_up";

    public TurtleUpTaskRecord(String toolCallId, long deadlineGameTime) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
    }

    @Override
    public String describe() {
        return TOOL_NAME;
    }
}
