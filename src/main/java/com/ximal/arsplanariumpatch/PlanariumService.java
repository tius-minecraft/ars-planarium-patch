package com.ximal.arsplanariumpatch;

import com.hollingsworth.arsnouveau.ArsNouveau;
import com.hollingsworth.arsnouveau.api.item.IWandable;
import com.hollingsworth.arsnouveau.common.block.DimBoundary;
import com.hollingsworth.arsnouveau.common.block.tile.PlanariumTile;
import com.hollingsworth.arsnouveau.common.network.Networking;
import com.hollingsworth.arsnouveau.common.network.PacketUpdateDimTile;
import com.hollingsworth.arsnouveau.common.world.dimension.PlanariumChunkGenerator;
import com.hollingsworth.arsnouveau.common.world.saved_data.DimMappingData;
import com.hollingsworth.arsnouveau.common.world.saved_data.JarDimData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * All server-side logic that replaces Ars Nouveau's per-planarium dimensions with cells in one
 * shared dimension.
 */
public final class PlanariumService {
    private PlanariumService() {
    }

    public static void initServer(MinecraftServer server) {
        PlanariumLayoutData layout = PlanariumLayoutData.from(server);
        int configured = configuredStride();
        if (layout.stride() <= 0) {
            layout.ensureStride(configured);
        } else if (layout.stride() != configured) {
            ArsPlanariumPatch.LOGGER.warn(
                    "This world uses planarium cell stride {} but the config says {}. Keeping the world value; "
                            + "the stride of an existing world cannot be changed.",
                    layout.stride(), configured);
        }
        PlanariumDimension.setGenerationStride(layout.stride());
    }

    public static int configuredStride() {
        int raw = Config.CELL_STRIDE.get();
        int clamped = Math.max(64, Math.min(4096, raw));
        return (clamped / 16) * 16;
    }

    public static int stride(MinecraftServer server) {
        return PlanariumLayoutData.from(server).stride();
    }

    /**
     * Returns (and creates if necessary) the layout entry for a planarium id. For planariums created
     * before this patch the id is the old dimension key; such entries are migrated on first access.
     */
    public static PlanariumLayoutData.Entry entryFor(ServerLevel tileLevel, ResourceKey<Level> key) {
        MinecraftServer server = tileLevel.getServer();
        PlanariumLayoutData layout = PlanariumLayoutData.from(server);

        PlanariumLayoutData.Entry entry = layout.byId(key.location());
        if (entry != null) {
            return entry;
        }

        String name = null;
        // Ars Nouveau always allocates planarium ids in the overworld's mapping data, even when the
        // block itself is placed in another dimension (see PlanariumTile.DimManager#getOrCreateLevel).
        DimMappingData mapping = DimMappingData.from(server.overworld());
        DimMappingData.Entry mapped = mapping.getByKey(key.location());
        if (mapped != null) {
            name = mapped.name();
        }
        if (name == null) {
            name = key.location().toString();
        }

        entry = layout.getOrCreate(name, key.location());
        migrateLegacyIfNeeded(tileLevel, key, entry);
        // Migration updates the spawn and legacy flag, so return the up-to-date entry.
        PlanariumLayoutData.Entry updated = layout.byId(entry.id());
        return updated != null ? updated : entry;
    }

    private static void migrateLegacyIfNeeded(ServerLevel tileLevel, ResourceKey<Level> key, PlanariumLayoutData.Entry entry) {
        MinecraftServer server = tileLevel.getServer();
        if (entry.legacy() || PlanariumDimension.isShared(key)) {
            return;
        }
        ServerLevel oldLevel = server.getLevel(key);
        if (oldLevel == null || !PlanariumDimension.hasPlanariumDimensionType(oldLevel)) {
            return;
        }
        migrate(oldLevel, entry);
    }

    /**
     * Copies the old per-planarium dimension contents (32x31x32 template at 0/1/0) into the cell
     * and records the old dimension for later unregistration.
     */
    public static void migrate(ServerLevel oldLevel, PlanariumLayoutData.Entry entry) {
        MinecraftServer server = oldLevel.getServer();
        ServerLevel shared = PlanariumDimension.level(server);
        if (shared == null) {
            ArsPlanariumPatch.LOGGER.error("Cannot migrate planarium '{}': shared dimension is missing", entry.name());
            return;
        }

        PlanariumLayoutData layout = PlanariumLayoutData.from(server);
        int stride = layout.stride();
        BlockPos base = PlanariumDimension.base(entry.cell(), stride);

        // Load the old cell and take a snapshot of its contents.
        forceChunks(oldLevel, BlockPos.ZERO, -1, -1, 2, 2, true);
        StructureTemplate template = new StructureTemplate();
        template.fillFromWorld(oldLevel, new BlockPos(0, 1, 0), new Vec3i(32, 31, 32), true, Blocks.AIR);
        forceChunks(oldLevel, BlockPos.ZERO, -1, -1, 2, 2, false);

        // Paste the snapshot into the new cell.
        int chunkX = base.getX() >> 4;
        int chunkZ = base.getZ() >> 4;
        forceChunks(shared, base, chunkX, chunkZ, chunkX + 2, chunkZ + 2, true);
        template.placeInWorld(shared, PlanariumDimension.templateOrigin(base), BlockPos.ZERO,
                new StructurePlaceSettings(), RandomSource.create(), 2);
        forceChunks(shared, base, chunkX, chunkZ, chunkX + 2, chunkZ + 2, false);

        // The old spawn is already expressed in local (0..31) coordinates: new = base + 1 + old.
        BlockPos oldSpawn = JarDimData.from(oldLevel).getSpawnPos();
        PlanariumLayoutData.Entry withSpawn = layout.setSpawn(entry, oldSpawn);
        layout.setLegacy(withSpawn, true);

        ArsPlanariumPatch.LOGGER.info("Migrated planarium '{}' from {} to shared cell {} (origin {})",
                entry.name(), oldLevel.dimension().location(), entry.cell(), base);
    }

    public static StructureTemplate loadTemplate(ServerLevel tileLevel, BlockPos ownerPos, ResourceKey<Level> key) {
        MinecraftServer server = tileLevel.getServer();
        ServerLevel shared = PlanariumDimension.level(server);
        if (shared == null) {
            return null;
        }

        PlanariumLayoutData.Entry entry = entryFor(tileLevel, key);
        PlanariumLayoutData layout = PlanariumLayoutData.from(server);
        BlockPos base = PlanariumDimension.base(entry.cell(), layout.stride());

        int chunkX = base.getX() >> 4;
        int chunkZ = base.getZ() >> 4;
        forceChunks(shared, ownerPos, chunkX, chunkZ, chunkX + 2, chunkZ + 2, true);

        StructureTemplate template = new StructureTemplate();
        template.fillFromWorld(shared, PlanariumDimension.templateOrigin(base),
                new Vec3i(32, 31, 32), true, Blocks.AIR);

        forceChunks(shared, ownerPos, chunkX, chunkZ, chunkX + 2, chunkZ + 2, false);
        return template;
    }

    public static PlanariumTile.DimManager.Entry getOrCreateTemplate(ServerLevel tileLevel, BlockPos ownerPos,
                                                                     ResourceKey<Level> key) {
        PlanariumTile.DimManager manager = PlanariumTile.dimManager;
        PlanariumTile.DimManager.Entry entry = manager.entries.get(key);
        if (entry != null && !entry.dirty() && entry.template() != null) {
            return entry;
        }

        StructureTemplate template = loadTemplate(tileLevel, ownerPos, key);
        if (template == null) {
            return null;
        }

        PlanariumTile.DimManager.Entry fresh =
                new PlanariumTile.DimManager.Entry(template, false, tileLevel.getGameTime());
        manager.entries.put(key, fresh);
        return fresh;
    }

    public static ServerLevel setDimension(PlanariumTile tile, String dimName, ServerLevel tileLevel) {
        MinecraftServer server = tileLevel.getServer();
        ServerLevel shared = PlanariumDimension.level(server);
        if (shared == null) {
            return tileLevel;
        }

        DimMappingData.Entry mapping = DimMappingData.from(server.overworld()).getOrCreateByName(dimName);
        ResourceLocation id = mapping.key();
        ResourceKey<Level> dimensionKey = ResourceKey.create(Registries.DIMENSION, id);

        // Creates the cell, or migrates a legacy planarium that already used this name/id.
        entryFor(tileLevel, dimensionKey);

        tile.key = dimensionKey;
        tile.name = Component.literal(dimName);

        PlanariumTile.DimManager.Entry managerEntry =
                getOrCreateTemplate(tileLevel, tile.getBlockPos(), dimensionKey);
        if (managerEntry != null) {
            tile.lastUpdated = managerEntry.lastUpdated();
            StructureTemplate template = managerEntry.template();
            if (template != null) {
                Networking.sendToNearbyClient(tile.getLevel(), tile.getBlockPos(),
                        new PacketUpdateDimTile(tile.getBlockPos(), template));
            }
            tile.updateBlock();
        }

        return shared;
    }

    public static void sendEntityTo(PlanariumTile tile, Entity entity) {
        if (tile.key == null || !(tile.getLevel() instanceof ServerLevel tileLevel)) {
            return;
        }

        MinecraftServer server = tileLevel.getServer();
        ServerLevel shared = PlanariumDimension.level(server);
        if (shared == null) {
            return;
        }

        PlanariumLayoutData layout = PlanariumLayoutData.from(server);
        PlanariumLayoutData.Entry entry = entryFor(tileLevel, tile.key);
        BlockPos spawn = entry.spawnAbsolute(layout.stride());

        JarDimData jarData = JarDimData.from(shared);
        // Do not overwrite the return position when jumping from one planarium into another.
        if (entity instanceof ServerPlayer && !PlanariumDimension.hasPlanariumDimensionType(tileLevel)) {
            jarData.setEnteredFrom(entity.getUUID(),
                    GlobalPos.of(tileLevel.dimension(), entity.blockPosition()), entity.getRotationVector());
        }

        entity.teleportTo(shared, spawn.getX() + 0.5, spawn.getY() + 1, spawn.getZ() + 0.5,
                Set.of(), entity.getYRot(), entity.getXRot());
    }

    public static void onBlockBroken(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        MinecraftServer server = level.getServer();
        if (!PlanariumDimension.hasPlanariumDimensionType(level)) {
            return;
        }

        BlockPos pos = event.getPos();
        boolean insideJar;

        if (PlanariumDimension.isShared(level)) {
            PlanariumLayoutData layout = PlanariumLayoutData.from(server);
            int stride = layout.stride();
            long cell = PlanariumDimension.cellOf(pos, stride);
            insideJar = PlanariumDimension.isInterior(cell, pos, stride);
            if (insideJar) {
                PlanariumLayoutData.Entry entry = layout.byCell(cell);
                if (entry != null) {
                    PlanariumTile.dimManager.markDirty(entry.dimensionKey());
                }
            }
        } else {
            // Legacy per-planarium dimension: keep the original logic.
            insideJar = PlanariumChunkGenerator.innerBox.contains(pos.getX(), pos.getY(), pos.getZ());
            if (insideJar) {
                PlanariumTile.dimManager.markDirty(level.dimension());
            }
        }

        if (event.getState().getBlock() instanceof DimBoundary && !insideJar) {
            if (event.getPlayer().canInteractWithBlock(pos, 4.0f)) {
                DimBoundary.playerAttemptedBreak(level, event.getPlayer());
            }
            event.setCanceled(true);
        }
    }

    public static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!PlanariumDimension.hasPlanariumDimensionType(level)) {
            return;
        }

        if (PlanariumDimension.isShared(level)) {
            PlanariumLayoutData layout = PlanariumLayoutData.from(level.getServer());
            long cell = PlanariumDimension.cellOf(event.getPos(), layout.stride());
            PlanariumLayoutData.Entry entry = layout.byCell(cell);
            if (entry != null) {
                PlanariumTile.dimManager.markDirty(entry.dimensionKey());
            }
        } else {
            PlanariumTile.dimManager.markDirty(level.dimension());
        }
    }

    /**
     * Sets the spawn point of the cell the player clicked in. Only called for the shared dimension.
     */
    public static IWandable.Result onFirstConnection(@Nullable GlobalPos storedPos, Level level, net.minecraft.world.entity.player.Player player) {
        if (!(level instanceof ServerLevel serverLevel)
                || storedPos == null
                || !level.dimension().equals(storedPos.dimension())) {
            return IWandable.Result.FAIL;
        }

        PlanariumLayoutData layout = PlanariumLayoutData.from(serverLevel.getServer());
        int stride = layout.stride();
        BlockPos pos = storedPos.pos();
        long cell = PlanariumDimension.cellOf(pos, stride);

        PlanariumLayoutData.Entry entry = layout.byCell(cell);
        if (entry == null || !PlanariumDimension.isInterior(cell, pos, stride)) {
            player.sendSystemMessage(Component.translatable("ars_nouveau.jar_spawn_out_of_bounds"));
        } else {
            layout.setSpawn(entry, PlanariumDimension.toLocal(pos, PlanariumDimension.base(cell, stride)));
            player.sendSystemMessage(Component.translatable("ars_nouveau.set_jar_spawn"));
        }
        return IWandable.Result.SUCCESS;
    }

    private static void forceChunks(ServerLevel level, BlockPos owner, int minX, int minZ, int maxX, int maxZ, boolean load) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                ArsNouveau.ticketController.forceChunk(level, owner, x, z, load, false);
            }
        }
    }
}
