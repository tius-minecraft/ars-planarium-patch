package com.ximal.arsplanariumpatch;

import com.hollingsworth.arsnouveau.ArsNouveau;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Constants and coordinate helpers for the shared planarium dimension.
 *
 * <p>Layout of one cell (origin = {@code base}):</p>
 * <pre>
 *   base+0        .. base+33  boundary box (34 blocks wide)
 *   base+1        .. base+32  planarium interior (32 blocks)
 *   y 0           .. y 32     boundary box (33 blocks tall)
 *   y 1           .. y 30     planarium interior (AABB from PlanariumChunkGenerator.innerBox)
 * </pre>
 */
public final class PlanariumDimension {
    public static final ResourceKey<Level> LEVEL = ResourceKey.create(
            Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(ArsPlanariumPatch.MODID, "planariums"));

    public static final int CONTENT_SIZE = 32;
    public static final int CONTENT_HEIGHT = 31;
    public static final int FOOTPRINT = 34;
    public static final int MIN_INTERIOR_Y = 1;
    public static final int MAX_INTERIOR_Y = 30;
    public static final int MIN_Y = 0;
    public static final int MAX_Y = 32;

    /** Default spawn block in local cell coordinates (the player is teleported on top of it). */
    public static final BlockPos DEFAULT_SPAWN = new BlockPos(7, 2, 7);

    /**
     * Stride used by the chunk generator. It is initialized from the world's layout data on server
     * start; chunk generation itself must not touch SavedData because it can run off-thread.
     */
    private static volatile int generationStride = 512;

    public static int generationStride() {
        return generationStride;
    }

    public static void setGenerationStride(int stride) {
        generationStride = stride;
    }

    @Nullable
    public static ServerLevel level(MinecraftServer server) {
        return server.getLevel(LEVEL);
    }

    public static boolean isShared(Level level) {
        return level.dimension().equals(LEVEL);
    }

    public static boolean isShared(ResourceKey<Level> key) {
        return LEVEL.equals(key);
    }

    /**
     * True for the shared dimension and for legacy per-planarium dimensions (both use ars_nouveau:jar).
     */
    public static boolean hasPlanariumDimensionType(Level level) {
        return level.dimensionTypeRegistration().unwrapKey()
                .map(key -> key.equals(ArsNouveau.DIMENSION_TYPE_KEY))
                .orElse(false);
    }

    public static boolean isPlanariumLevel(Level level) {
        return isShared(level) || hasPlanariumDimensionType(level);
    }

    public static long cellOf(BlockPos pos, int stride) {
        return PlanariumCell.cellOf(pos, stride);
    }

    public static BlockPos base(long cell, int stride) {
        return PlanariumCell.unpack(cell).base(stride);
    }

    /**
     * Interior of a cell: 1..32 on X/Z and 1..30 on Y relative to the base.
     */
    public static boolean isInterior(long cell, BlockPos pos, int stride) {
        BlockPos base = base(cell, stride);
        int localX = pos.getX() - base.getX();
        int localZ = pos.getZ() - base.getZ();
        return localX >= 1 && localX <= CONTENT_SIZE
                && localZ >= 1 && localZ <= CONTENT_SIZE
                && pos.getY() >= MIN_INTERIOR_Y && pos.getY() <= MAX_INTERIOR_Y;
    }

    /** Converts an absolute block position inside a cell into "template local" coordinates (0..31). */
    public static BlockPos toLocal(BlockPos absolute, BlockPos base) {
        return new BlockPos(absolute.getX() - base.getX() - 1, absolute.getY(), absolute.getZ() - base.getZ() - 1);
    }

    /** Converts "template local" coordinates (0..31) into absolute block coordinates. */
    public static BlockPos localToAbsolute(BlockPos local, BlockPos base) {
        return new BlockPos(base.getX() + 1 + local.getX(), local.getY(), base.getZ() + 1 + local.getZ());
    }

    /** Origin of the template snapshot area (one block inside the boundary). */
    public static BlockPos templateOrigin(BlockPos base) {
        return new BlockPos(base.getX() + 1, 1, base.getZ() + 1);
    }

    private PlanariumDimension() {
    }
}
