package com.ximal.arsplanariumpatch;

import net.minecraft.core.BlockPos;

/**
 * Identifies a single planarium cell in the shared dimension's infinite grid.
 */
public record PlanariumCell(int x, int z) {
    /**
     * 11 bits per axis give ~4.2 million possible cells, which is far more than any world will
     * ever need, while keeping every cell origin inside the vanilla world border: the largest
     * supported stride (4096) times the largest cell index (2047) is ~8.4M blocks, and the
     * default world border spans +/-15M blocks. (The border is shared by all levels, and the
     * chunk generator cannot move it.)
     */
    public static final int HASH_BITS = 11;
    public static final int HASH_MASK = (1 << HASH_BITS) - 1;

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
     * Converts a block position (boundary or interior) into the cell that contains it.
     * Cell origins are always multiples of the stride, so a simple floor division is enough.
     */
    public static long cellOf(BlockPos pos, int stride) {
        return pack(Math.floorDiv(pos.getX(), stride), Math.floorDiv(pos.getZ(), stride));
    }

    public static long hashCell(String name) {
        long hash = hash64(name);
        int x = (int) (hash & HASH_MASK);
        int z = (int) ((hash >>> HASH_BITS) & HASH_MASK);
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
