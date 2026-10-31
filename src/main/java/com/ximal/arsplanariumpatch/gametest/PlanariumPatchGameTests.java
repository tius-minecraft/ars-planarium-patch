package com.ximal.arsplanariumpatch.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.hollingsworth.arsnouveau.ArsNouveau;
import com.hollingsworth.arsnouveau.common.mixin.structure.StructureTemplateAccessor;
import com.hollingsworth.arsnouveau.common.block.tile.PlanariumTile;
import com.hollingsworth.arsnouveau.common.world.dimension.PlanariumChunkGenerator;
import com.hollingsworth.arsnouveau.common.world.saved_data.JarDimData;
import com.hollingsworth.arsnouveau.setup.registry.BlockRegistry;
import com.mojang.serialization.JsonOps;
import com.ximal.arsplanariumpatch.ArsPlanariumPatch;
import com.ximal.arsplanariumpatch.PlanariumCell;
import com.ximal.arsplanariumpatch.PlanariumDimension;
import com.ximal.arsplanariumpatch.PlanariumGridChunkGenerator;
import com.ximal.arsplanariumpatch.PlanariumLayoutData;
import com.ximal.arsplanariumpatch.PlanariumService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

@GameTestHolder(ArsPlanariumPatch.MODID)
@PrefixGameTestTemplate(false)
public class PlanariumPatchGameTests {

    /**
     * GameTest levels have no players, so entities moved into the shared dimension are never
     * entity-ticking and cannot be found through {@code getEntitiesOfClass}. Instead the test
     * captures the join event, which also proves that the entity reached the destination level.
     */
    private static final AtomicReference<UUID> EXPECTED_ENTITY = new AtomicReference<>();
    private static final AtomicReference<BlockPos> JOINED_AT = new AtomicReference<>();
    private static boolean joinListenerRegistered;

    private static void ensureJoinListener() {
        if (joinListenerRegistered) {
            return;
        }
        joinListenerRegistered = true;
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, EntityJoinLevelEvent.class, event -> {
            UUID expected = EXPECTED_ENTITY.get();
            if (expected != null && expected.equals(event.getEntity().getUUID())
                    && event.getLevel().dimension().equals(PlanariumDimension.LEVEL)) {
                JOINED_AT.set(event.getEntity().blockPosition());
            }
        });
    }

    @GameTest(template = "empty")
    public static void hashingIsDeterministic(GameTestHelper helper) {
        int maxIndex = PlanariumCell.maxCellIndex(1_000_000, 512);
        long first = PlanariumCell.hashCell("test-planarium", maxIndex);
        long second = PlanariumCell.hashCell("test-planarium", maxIndex);
        long other = PlanariumCell.hashCell("test-planarium-2", maxIndex);

        PlanariumCell cell = PlanariumCell.unpack(first);
        helper.assertTrue(first == second, "The same name must always hash to the same cell");
        helper.assertTrue(first != other, "Different names should hash to different cells");
        helper.assertTrue(cell.x() >= -maxIndex && cell.x() <= maxIndex,
                "Cell x must stay inside the configured border radius");
        helper.assertTrue(cell.z() >= -maxIndex && cell.z() <= maxIndex,
                "Cell z must stay inside the configured border radius");
        helper.succeed();
    }

    /**
     * The world border is shared by every level and cannot be moved by the chunk generator, so the
     * furthest possible cell boundary must stay inside it.
     */
    @GameTest(template = "empty")
    public static void cellOriginsStayInsideWorldBorder(GameTestHelper helper) {
        int largestStride = 4096;
        int radius = PlanariumService.configuredBorderRadius();
        int maxIndex = PlanariumCell.maxCellIndex(radius, largestStride);
        int footprint = PlanariumDimension.FOOTPRINT;
        BlockPos positive = new PlanariumCell(maxIndex, maxIndex).base(largestStride);
        BlockPos negative = new PlanariumCell(-maxIndex, -maxIndex).base(largestStride);
        int borderHalfSize = 15_000_000;

        helper.assertTrue(positive.getX() + footprint - 1 <= radius
                        && positive.getZ() + footprint - 1 <= radius,
                "Positive cells must fit inside the configured radius, highest origin is " + positive);
        helper.assertTrue(negative.getX() >= -radius && negative.getZ() >= -radius,
                "Negative cells must fit inside the configured radius, lowest origin is " + negative);
        helper.assertTrue(positive.getX() + footprint - 1 <= borderHalfSize
                        && negative.getX() >= -borderHalfSize,
                "Cell boundaries must stay inside the world border");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void layoutAllocationIsStable(GameTestHelper helper) {
        int radius = PlanariumService.configuredBorderRadius();
        PlanariumLayoutData layout = new PlanariumLayoutData();
        layout.ensureStride(512);

        ResourceLocation alphaId = ResourceLocation.fromNamespaceAndPath(ArsPlanariumPatch.MODID, "test_alpha");
        ResourceLocation betaId = ResourceLocation.fromNamespaceAndPath(ArsPlanariumPatch.MODID, "test_beta");

        PlanariumLayoutData.Entry alpha = layout.getOrCreate("alpha", alphaId, radius);
        PlanariumLayoutData.Entry beta = layout.getOrCreate("beta", betaId, radius);

        helper.assertTrue(layout.getOrCreate("alpha", alphaId, radius) == alpha,
                "Repeated lookup must return the same entry");
        helper.assertTrue(alpha.cell() != beta.cell(), "Two different planariums must not share a cell");
        helper.assertTrue(layout.byCell(alpha.cell()) == alpha, "Cell index must resolve back to the entry");
        helper.assertTrue(layout.byId(alphaId) == alpha, "Id index must resolve back to the entry");
        helper.assertTrue(alpha.spawnLocal().equals(PlanariumDimension.DEFAULT_SPAWN), "Default spawn must be used");
        BlockPos expectedSpawn = PlanariumDimension.localToAbsolute(alpha.spawnLocal(),
                PlanariumDimension.base(alpha.cell(), 512));
        helper.assertTrue(alpha.spawnAbsolute(512).equals(expectedSpawn),
                "Spawn must be translated into the cell");
        helper.succeed();
    }

    /**
     * Every newly allocated cell must fit its whole 34-block boundary box inside the configured
     * radius, including cells with negative indices.
     */
    @GameTest(template = "empty")
    public static void allocationRespectsBorderRadius(GameTestHelper helper) {
        int radius = 4096;
        int stride = 512;
        int footprint = PlanariumDimension.FOOTPRINT;
        PlanariumLayoutData layout = new PlanariumLayoutData();
        layout.ensureStride(stride);

        for (int i = 0; i < 100; i++) {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(ArsPlanariumPatch.MODID, "radius_" + i);
            PlanariumLayoutData.Entry entry = layout.getOrCreate("radius-" + i, id, radius);
            BlockPos base = PlanariumDimension.base(entry.cell(), stride);
            helper.assertTrue(base.getX() >= -radius && base.getX() + footprint - 1 <= radius,
                    "Cell x must fit inside the radius: " + base);
            helper.assertTrue(base.getZ() >= -radius && base.getZ() + footprint - 1 <= radius,
                    "Cell z must fit inside the radius: " + base);
        }
        helper.succeed();
    }

    /**
     * Validates the static datapack dimension file against the real codecs. GameTestServer bakes an
     * empty level-stem registry (it only knows the flat preset), so the file is never loaded there;
     * decoding it manually also proves that every registry entry it references exists.
     */
    @GameTest(template = "empty")
    public static void dimensionJsonIsValid(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ResourceLocation file = ArsPlanariumPatch.id("dimension/planariums.json");
        var resource = server.getResourceManager().getResource(file);
        if (resource.isEmpty()) {
            helper.fail("Dimension file " + file + " is missing from the mod resources");
            return;
        }

        LevelStem stem;
        try (var reader = resource.get().openAsReader()) {
            JsonElement json = JsonParser.parseReader(reader);
            RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
            stem = LevelStem.CODEC.parse(ops, json).getOrThrow();
        } catch (Exception e) {
            helper.fail("Could not decode " + file + ": " + e);
            return;
        }

        helper.assertTrue(stem.type().is(PlanariumDimension.DIMENSION_TYPE),
                "Dimension must use the compact ars_planarium_patch:planariums dimension type");
        DimensionType type = stem.type().value();
        helper.assertTrue(type.minY() == -64 && type.height() == 128 && type.logicalHeight() == 128,
                "Shared dimension type must stay at height 128 (min_y -64, logical_height 128)");
        helper.assertTrue(stem.generator() instanceof PlanariumGridChunkGenerator,
                "Dimension must use the grid chunk generator");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void cellTemplateSnapshot(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel shared = ensureSharedLevel(server);

        ServerLevel overworld = server.overworld();
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath(ArsPlanariumPatch.MODID, "gametest_cell"));

        PlanariumLayoutData layout = PlanariumLayoutData.from(server);
        PlanariumLayoutData.Entry entry = PlanariumService.entryFor(overworld, key);
        BlockPos base = PlanariumDimension.base(entry.cell(), layout.stride());
        BlockPos origin = PlanariumDimension.templateOrigin(base);

        helper.assertTrue(shared.getBlockState(base).is(BlockRegistry.DIM_BOUNDARY.get()),
                "Grid chunk generator must place the cell boundary");
        helper.assertTrue(shared.getBlockState(origin).isAir(), "Fresh cell interior must be air");
        helper.assertTrue(shared.getBlockEntity(base.offset(1, -1, 1))
                        instanceof com.hollingsworth.arsnouveau.common.block.tile.PlanariumProjectorTile,
                "The projector must be created with its absolute position so the client renderer can anchor to it");

        shared.setBlockAndUpdate(origin, Blocks.STONE.defaultBlockState());
        try {
            StructureTemplate template = PlanariumService.loadTemplate(overworld, BlockPos.ZERO, key);
            helper.assertTrue(template != null, "Template snapshot must be created");
            helper.assertTrue(template.getSize().equals(new Vec3i(32, 31, 32)), "Template size must be 32x31x32");

            boolean found = ((StructureTemplateAccessor) template).getPalettes().stream()
                    .flatMap(palette -> palette.blocks().stream())
                    .anyMatch(block -> block.pos().equals(BlockPos.ZERO) && block.state().is(Blocks.STONE));
            helper.assertTrue(found, "Blocks placed inside the cell must appear in the local snapshot");
        } finally {
            shared.setBlockAndUpdate(origin, Blocks.AIR.defaultBlockState());
        }

        helper.succeed();
    }

    /**
     * Drives a real PlanariumTile to exercise the two Tile mixins: assigning the virtual dimension
     * key/cell and teleporting an entity into that cell.
     */
    @GameTest(template = "empty", timeoutTicks = 300)
    public static void planariumTileUsesSharedCell(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        ServerLevel shared = ensureSharedLevel(server);

        BlockPos tilePos = helper.absolutePos(new BlockPos(1, 2, 1));
        PlanariumTile tile = new PlanariumTile(tilePos, BlockRegistry.PLANARIUM.get().defaultBlockState());
        tile.setLevel(level);

        ServerLevel destination = tile.setDimension("gametest_tile", level);
        helper.assertTrue(destination == shared, "setDimension must resolve to the shared level");
        helper.assertTrue(tile.key != null, "setDimension must assign the planarium's virtual key");

        PlanariumLayoutData layout = PlanariumLayoutData.from(server);
        PlanariumLayoutData.Entry entry = layout.byId(tile.key.location());
        helper.assertTrue(entry != null, "setDimension must allocate a cell for the planarium");

        BlockPos spawn = entry.spawnAbsolute(layout.stride());

        Pig pig = EntityType.PIG.create(level);
        helper.assertTrue(pig != null, "Could not create the test entity");
        pig.moveTo(tilePos.getX() + 0.5, tilePos.getY() + 1, tilePos.getZ() + 0.5, 0.0f, 0.0f);
        level.addFreshEntity(pig);

        ensureJoinListener();
        EXPECTED_ENTITY.set(pig.getUUID());
        JOINED_AT.set(null);
        try {
            tile.sendEntityTo(pig);
        } finally {
            EXPECTED_ENTITY.set(null);
        }

        // A dimension change creates a new entity instance and removes the old one.
        helper.assertTrue(pig.isRemoved(), "The original entity must be removed after changing dimensions");
        BlockPos joined = JOINED_AT.get();
        helper.assertTrue(joined != null, "sendEntityTo must add the entity to the shared dimension");
        helper.assertTrue(joined.equals(spawn.offset(0, 1, 0)),
                "sendEntityTo must use the cell's spawn point, expected " + spawn.offset(0, 1, 0)
                        + " but was " + joined);

        helper.succeed();
    }

    /**
     * The update must not be marked as delivered while no player could receive it; otherwise a
     * planarium on a Sable sub-level stays stale until the client reconnects.
     */
    @GameTest(template = "empty", timeoutTicks = 300)
    public static void templateDeliveryRequiresRecipients(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        ensureSharedLevel(server);

        BlockPos tilePos = helper.absolutePos(new BlockPos(1, 2, 1));
        PlanariumTile tile = new PlanariumTile(tilePos, BlockRegistry.PLANARIUM.get().defaultBlockState());
        tile.setLevel(level);
        tile.setDimension("gametest_delivery", level);

        PlanariumTile.DimManager.Entry entry = PlanariumService.getOrCreateTemplate(level, tile.getBlockPos(), tile.key);
        helper.assertTrue(entry != null && entry.template() != null, "A fresh entry must be available");

        tile.lastUpdated = 4242L;
        helper.assertTrue(!PlanariumService.sendTemplate(level, tile, entry),
                "Without a tracking player the update must be reported as undelivered");
        helper.assertTrue(tile.lastUpdated == 4242L,
                "Without a tracking player the update must stay pending");
        helper.succeed();
    }

    /**
     * A queued refresh must reload the cell (and pick up blocks placed after the previous snapshot).
     */
    @GameTest(template = "empty", timeoutTicks = 300)
    public static void scheduledRefreshSnapshotsCell(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        ServerLevel shared = ensureSharedLevel(server);

        BlockPos tilePos = helper.absolutePos(new BlockPos(1, 2, 1));
        PlanariumTile tile = new PlanariumTile(tilePos, BlockRegistry.PLANARIUM.get().defaultBlockState());
        tile.setLevel(level);
        tile.setDimension("gametest_scheduled_refresh", level);

        PlanariumLayoutData layout = PlanariumLayoutData.from(server);
        PlanariumLayoutData.Entry layoutEntry = layout.byId(tile.key.location());
        helper.assertTrue(layoutEntry != null, "setDimension must allocate a layout entry");
        BlockPos origin = PlanariumDimension.templateOrigin(
                PlanariumDimension.base(layoutEntry.cell(), layout.stride()));

        shared.setBlockAndUpdate(origin, Blocks.STONE.defaultBlockState());
        try {
            PlanariumTile.dimManager.markDirty(tile.key);
            PlanariumTile.DimManager.Entry dirty = PlanariumTile.dimManager.entries.get(tile.key);
            helper.assertTrue(dirty != null && dirty.dirty(), "The cell must be marked dirty");

            PlanariumService.scheduleImmediateRefresh(tile.key, level);
            PlanariumService.tick(level, tile);

            PlanariumTile.DimManager.Entry refreshed = PlanariumTile.dimManager.entries.get(tile.key);
            helper.assertTrue(refreshed != null && !refreshed.dirty(),
                    "A scheduled tick must reload the cell");
            helper.assertTrue(refreshed != dirty, "The stale entry must be replaced");

            boolean found = ((StructureTemplateAccessor) refreshed.template()).getPalettes().stream()
                    .flatMap(palette -> palette.blocks().stream())
                    .anyMatch(block -> block.pos().equals(BlockPos.ZERO) && block.state().is(Blocks.STONE));
            helper.assertTrue(found, "The refreshed snapshot must contain the new block");
        } finally {
            shared.setBlockAndUpdate(origin, Blocks.AIR.defaultBlockState());
        }
        helper.succeed();
    }

    /**
     * Without an event there is nothing to refresh: a dirty cell must not be re-read (and its chunks
     * must not be force-loaded) just because the tile ticked.
     */
    @GameTest(template = "empty", timeoutTicks = 300)
    public static void unscheduledDirtyCellIsNotReloaded(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        ensureSharedLevel(server);

        BlockPos tilePos = helper.absolutePos(new BlockPos(1, 2, 1));
        PlanariumTile tile = new PlanariumTile(tilePos, BlockRegistry.PLANARIUM.get().defaultBlockState());
        tile.setLevel(level);
        tile.setDimension("gametest_unscheduled_refresh", level);

        PlanariumTile.dimManager.markDirty(tile.key);
        PlanariumTile.DimManager.Entry dirty = PlanariumTile.dimManager.entries.get(tile.key);
        helper.assertTrue(dirty != null && dirty.dirty(), "The cell must be marked dirty");

        PlanariumService.tick(level, tile);

        PlanariumTile.DimManager.Entry after = PlanariumTile.dimManager.entries.get(tile.key);
        helper.assertTrue(after == dirty, "A tick without a scheduled refresh must not touch the cell");
        helper.assertTrue(after.dirty(), "The dirty flag must remain until a refresh is scheduled");
        helper.succeed();
    }

    /**
     * Exercises the one-time copy from an old per-planarium dimension into a cell of the shared
     * dimension (content + spawn), which cannot happen in a normal GameTest world.
     */
    @GameTest(template = "empty", timeoutTicks = 300)
    public static void legacyPlanariumIsMigrated(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel shared = ensureSharedLevel(server);

        ResourceKey<Level> legacyKey = ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath(ArsPlanariumPatch.MODID, "gametest_legacy"));
        ServerLevel legacy = ensureLevel(server, legacyKey, new PlanariumChunkGenerator(server));

        legacy.setBlockAndUpdate(new BlockPos(0, 1, 0), Blocks.GOLD_BLOCK.defaultBlockState());
        BlockPos legacySpawn = new BlockPos(3, 4, 5);
        JarDimData.from(legacy).setSpawnPos(legacySpawn);

        PlanariumLayoutData layout = PlanariumLayoutData.from(server);
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(ArsPlanariumPatch.MODID, "gametest_legacy");
        PlanariumLayoutData.Entry entry = layout.getOrCreate("gametest_legacy", id,
                PlanariumService.configuredBorderRadius());
        PlanariumService.migrate(legacy, entry);

        PlanariumLayoutData.Entry migrated = layout.byId(id);
        helper.assertTrue(migrated != null && migrated.legacy(),
                "Migration must mark the old dimension for later unregistration");
        helper.assertTrue(migrated.spawnLocal().equals(legacySpawn),
                "Migration must keep the spawn point of the old dimension");

        BlockPos base = PlanariumDimension.base(migrated.cell(), layout.stride());
        helper.assertTrue(shared.getBlockState(PlanariumDimension.templateOrigin(base)).is(Blocks.GOLD_BLOCK),
                "Migration must copy the old cell contents into the shared cell");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void legacyDimensionIsUnregistered(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ensureSharedLevel(server);

        ResourceKey<Level> legacyKey = ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath(ArsPlanariumPatch.MODID, "gametest_legacy_cleanup"));
        ServerLevel legacy = ensureLevel(server, legacyKey, new PlanariumChunkGenerator(server));

        PlanariumLayoutData layout = PlanariumLayoutData.from(server);
        PlanariumLayoutData.Entry entry = layout.getOrCreate("gametest_legacy_cleanup", legacyKey.location(),
                PlanariumService.configuredBorderRadius());
        PlanariumService.migrate(legacy, entry);

        helper.runAfterDelay(130, () -> {
            helper.assertTrue(server.getLevel(legacyKey) == null,
                    "The migrated legacy dimension should be unregistered");
            helper.succeed();
        });
    }

    /**
     * The dedicated server gets the shared dimension from the datapack, but GameTestServer only bakes
     * the flat preset's dimensions. Create it the same way Ars Nouveau creates planarium dimensions
     * so the snapshot logic can still be exercised here.
     */
    private static ServerLevel ensureSharedLevel(MinecraftServer server) {
        return ensureLevel(server, PlanariumDimension.LEVEL, new PlanariumGridChunkGenerator(server));
    }

    private static ServerLevel ensureLevel(MinecraftServer server, ResourceKey<Level> key, ChunkGenerator generator) {
        ServerLevel level = server.getLevel(key);
        if (level != null) {
            return level;
        }

        try {
            Class<?> clazz = Class.forName("net.commoble.infiniverse.internal.DimensionManager");
            Object manager = clazz.getField("INSTANCE").get(null);
            Method method = clazz.getMethod("getOrCreateLevel",
                    MinecraftServer.class, ResourceKey.class, Supplier.class);
            Supplier<LevelStem> stem = () -> new LevelStem(
                    server.registryAccess().registryOrThrow(Registries.DIMENSION_TYPE)
                            .getHolderOrThrow(ArsNouveau.DIMENSION_TYPE_KEY),
                    generator);
            return (ServerLevel) method.invoke(manager, server, key, stem);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not create dimension " + key.location() + " for the test", e);
        }
    }
}
