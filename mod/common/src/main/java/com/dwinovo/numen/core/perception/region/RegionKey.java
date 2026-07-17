package com.dwinovo.numen.core.perception.region;

import net.minecraft.core.BlockPos;

/**
 * The 3D region address of the L2 regional-observation layer (Wave C of the
 * append-only world-cognition design). Granularity is 16×64×16, so the region
 * grid aligns exactly to one chunk column in X/Z and a 4-section band in Y:
 *
 * <pre>
 *   rx = floorDiv(x, 16);  ry = floorDiv(y, 64);  rz = floorDiv(z, 16)
 * </pre>
 *
 * <p>{@code ry} is <strong>mandatory</strong> even though v1 only summarises the
 * surface band — a cave, the surface, and the sky sharing one {@code (rx, rz)}
 * must never collapse into a single summary. Because {@code rx == chunkX} and
 * {@code rz == chunkZ}, the region maps to a single loaded chunk, which keeps the
 * observation scan cheap (one chunk's block-entity map + a fixed heightmap grid).
 *
 * @param dim the dimension id ({@code minecraft:overworld}), part of the identity
 */
public record RegionKey(String dim, int rx, int ry, int rz) {

    /** Region granularity in blocks (X and Z). */
    public static final int SIZE_XZ = 16;
    /** Region granularity in blocks (Y). */
    public static final int SIZE_Y = 64;

    public static RegionKey of(String dim, int x, int y, int z) {
        return new RegionKey(dim, Math.floorDiv(x, SIZE_XZ), Math.floorDiv(y, SIZE_Y), Math.floorDiv(z, SIZE_XZ));
    }

    public static RegionKey of(String dim, BlockPos pos) {
        return of(dim, pos.getX(), pos.getY(), pos.getZ());
    }

    /** The chunk X this region sits in (== {@link #rx}). */
    public int chunkX() { return rx; }

    /** The chunk Z this region sits in (== {@link #rz}). */
    public int chunkZ() { return rz; }

    /** World Y (inclusive) of the bottom of the region's band. */
    public int minY() { return ry * SIZE_Y; }

    /** World Y (exclusive) of the top of the region's band. */
    public int maxY() { return ry * SIZE_Y + SIZE_Y; }

    /** Block position at the horizontal centre of the region and the middle of its Y band. */
    public BlockPos centerPos() {
        return new BlockPos(rx * SIZE_XZ + 8, ry * SIZE_Y + SIZE_Y / 2, rz * SIZE_XZ + 8);
    }

    /** Stable string key for maps and the on-disk region table (e.g. {@code minecraft:overworld|0|1|-1}). */
    public String storageKey() {
        return dim + "|" + rx + "|" + ry + "|" + rz;
    }

    /** Human/agent-facing label (e.g. {@code overworld(0,1,-1)}), dimension prefix stripped for brevity. */
    public String label() {
        String d = dim.startsWith("minecraft:") ? dim.substring("minecraft:".length()) : dim;
        return d + "(" + rx + "," + ry + "," + rz + ")";
    }

    /** Parse a {@link #storageKey()} back into a key (used when loading the region table). */
    public static RegionKey parse(String storageKey) {
        int last = storageKey.lastIndexOf('|');
        int mid = storageKey.lastIndexOf('|', last - 1);
        int first = storageKey.lastIndexOf('|', mid - 1);
        String dim = storageKey.substring(0, first);
        int rx = Integer.parseInt(storageKey.substring(first + 1, mid));
        int ry = Integer.parseInt(storageKey.substring(mid + 1, last));
        int rz = Integer.parseInt(storageKey.substring(last + 1));
        return new RegionKey(dim, rx, ry, rz);
    }
}
