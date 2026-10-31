package com.ximal.arsplanariumpatch;

import net.minecraft.core.BlockPos;

/**
 * Identifies a single planarium cell in the shared dimension's infinite grid.
 */
public record PlanariumCell(int x, int z) {
    public static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public static PlanariumCell unpack(long packed) {
        return new PlanariumCell((int) (packed >> 32), (int) packed);
    }

    public long packed() {
        return pack(x, z);
    }

    public BlockPos base(int stride) {
        return new BlockPos(Math.multiplyExact(x, stride), 0, Math.multiplyExact(z, stride));
    }

    /**
     * Largest absolute cell index whose full boundary box still fits inside the configured square
     * border radius. A cell origin is {@code index * stride} and its box extends
     * {@link PlanariumDimension#FOOTPRINT} - 1 blocks further out, so the outermost origin must
     * leave room for the whole footprint.
     */
    public static int maxCellIndex(int radius, int stride) {
        if (stride <= 0) {
            return 0;
        }
        long usable = (long) radius - (PlanariumDimension.FOOTPRINT - 1);
        if (usable < 0) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, usable / stride);
    }

    /**
     * Converts a block position (boundary or interior) into the cell that contains it.
     * Cell origins are always multiples of the stride, so a simple floor division is enough.
     */
    public static long cellOf(BlockPos pos, int stride) {
        return pack(Math.floorDiv(pos.getX(), stride), Math.floorDiv(pos.getZ(), stride));
    }

    /**
     * Hashes a planarium name into the signed cell range {@code [-maxIndex, maxIndex]}.
     */
    public static long hashCell(String name, int maxIndex) {
        long hash = hash64(name);
        int span = 2 * maxIndex + 1;
        int x = (int) Math.floorMod(hash, (long) span) - maxIndex;
        int z = (int) Math.floorMod(hash >>> 32, (long) span) - maxIndex;
        return pack(x, z);
    }

    /**
     * FNV-1a over UTF-8 bytes followed by a SplitMix64 finalizer.
     */
    public static long hash64(String name) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : name.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            hash ^= (b & 0xFFL);
            hash *= 0x100000001b3L;
        }
        hash ^= hash >>> 30;
        hash *= 0xbf58476d1ce4e5b9L;
        hash ^= hash >>> 27;
        hash *= 0x94d049bb133111ebL;
        hash ^= hash >>> 31;
        return hash;
    }
}
