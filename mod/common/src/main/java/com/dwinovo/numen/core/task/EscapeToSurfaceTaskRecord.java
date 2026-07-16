package com.dwinovo.numen.core.task;

/**
 * Typed task descriptor for the {@code escape_to_surface} muscle: climb a vertical
 * shaft straight up to the open-air surface (dig head-room + pillar underfoot,
 * sidestepping a fluid/unbreakable ceiling). No arguments — the target is the
 * heightmap surface of the body's own column. The pit-accident's direct antidote.
 */
public final class EscapeToSurfaceTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "escape_to_surface";

    public EscapeToSurfaceTaskRecord(String toolCallId, long deadlineGameTime) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
    }

    @Override
    public String describe() {
        return TOOL_NAME;
    }
}
