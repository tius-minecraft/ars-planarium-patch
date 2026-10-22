package com.ximal.arsplanariumpatch;

import com.hollingsworth.arsnouveau.common.block.tile.PlanariumProjectorTile;
import com.hollingsworth.arsnouveau.setup.registry.BlockRegistry;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Chunk generator for the shared planarium dimension.
 *
 * <p>It deterministically builds a 34x33x34 boundary box in every grid cell. The cell index is derived
 * from the chunk position alone (never from saved data), so a cell always has its boundary, no matter
 * when its chunks are generated. Everything outside the cells stays void.</p>
 */
public class PlanariumGridChunkGenerator extends ChunkGenerator {
    public static final int MIN_Y = 0;
    public static final int MAX_Y = 32;

    private final Holder<Biome> biome;

    public static MapCodec<PlanariumGridChunkGenerator> makeCodec() {
        return Biome.CODEC.fieldOf("biome")
                .xmap(PlanariumGridChunkGenerator::new, PlanariumGridChunkGenerator::biome);
    }

    public PlanariumGridChunkGenerator(MinecraftServer server) {
        this(server.registryAccess().registryOrThrow(Registries.BIOME)
                .getHolderOrThrow(com.hollingsworth.arsnouveau.ArsNouveau.BIOME_KEY));
    }

    public PlanariumGridChunkGenerator(Holder<Biome> biome) {
        super(new FixedBiomeSource(biome));
        this.biome = biome;
    }

    public Holder<Biome> biome() {
        return biome;
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return ArsPlanariumPatch.GRID_CHUNK_GENERATOR.get();
    }

    @Override
    public void applyCarvers(WorldGenRegion world, long seed, RandomState random, BiomeManager biomeManager,
                             StructureManager structureManager, ChunkAccess chunkAccess, GenerationStep.Carving carvingStep) {
        // noop
    }

    @Override
    public void buildSurface(WorldGenRegion region, StructureManager structures, RandomState random, ChunkAccess chunk) {
        int stride = PlanariumDimension.generationStride();
        int chunksPerCell = stride >> 4;
        if (chunksPerCell <= 0) {
            return;
        }

        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;
        int cellX = Math.floorDiv(chunkX, chunksPerCell);
        int cellZ = Math.floorDiv(chunkZ, chunksPerCell);
        int localChunkX = chunkX - cellX * chunksPerCell;
        int localChunkZ = chunkZ - cellZ * chunksPerCell;

        // A cell is 34 blocks wide, i.e. it always occupies a 3x3 chunk area.
        if (localChunkX > 2 || localChunkZ > 2) {
            return;
        }

        int baseX = cellX * stride;
        int baseZ = cellZ * stride;
        int minX = baseX;
        int maxX = baseX + 33;
        int minZ = baseZ;
        int maxZ = baseZ + 33;

        int chunkWorldX = chunkX * 16;
        int chunkWorldZ = chunkZ * 16;

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int worldPosX = chunkWorldX + x;
                int worldPosZ = chunkWorldZ + z;
                if (worldPosX < minX || worldPosX > maxX || worldPosZ < minZ || worldPosZ > maxZ) {
                    continue;
                }

                for (int y = MIN_Y; y <= MAX_Y; y++) {
                    boolean isEdge = worldPosX == minX || worldPosX == maxX
                            || worldPosZ == minZ || worldPosZ == maxZ
                            || y == MIN_Y || y == MAX_Y;

                    if (worldPosX == baseX + 1 && y == MIN_Y && worldPosZ == baseZ + 1) {
                        BlockPos local = new BlockPos(x, y - 1, z);
                        BlockPos projectorPos = new BlockPos(worldPosX, y - 1, worldPosZ);
                        chunk.setBlockState(local, BlockRegistry.PLANARIUM_PROJECTOR.defaultBlockState(), false);
                        chunk.setBlockEntity(new PlanariumProjectorTile(projectorPos, BlockRegistry.PLANARIUM_PROJECTOR.defaultBlockState()));
                        chunk.setBlockState(new BlockPos(x, y, z), BlockRegistry.DIM_BOUNDARY.defaultBlockState(), false);
                    } else if (isEdge) {
                        chunk.setBlockState(new BlockPos(x, y, z), BlockRegistry.DIM_BOUNDARY.defaultBlockState(), false);
                    }
                }
            }
        }
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion region) {
        // noop
    }

    @Override
    public int getGenDepth() {
        return 33;
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState random, StructureManager structures, ChunkAccess chunk) {
        return CompletableFuture.completedFuture(chunk);
    }

    @Override
    public int getSeaLevel() {
        return 0;
    }

    @Override
    public int getMinY() {
        return -64;
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types heightmapType, LevelHeightAccessor level, RandomState random) {
        return 0;
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState random) {
        return new NoiseColumn(0, new BlockState[0]);
    }

    @Override
    public void addDebugScreenInfo(List<String> stringsToRender, RandomState random, BlockPos pos) {
        // no info
    }

    @Nullable
    @Override
    public Pair<BlockPos, Holder<Structure>> findNearestMapStructure(ServerLevel level, HolderSet<Structure> structures,
                                                                    BlockPos pos, int range, boolean skipKnownStructures) {
        return null;
    }

    @Override
    public void applyBiomeDecoration(WorldGenLevel world, ChunkAccess chunkAccess, StructureManager structures) {
        // noop
    }

    @Override
    public int getSpawnHeight(LevelHeightAccessor level) {
        return 1;
    }

    @Override
    public void createReferences(WorldGenLevel world, StructureManager structures, ChunkAccess chunk) {
        // no structures
    }
}
