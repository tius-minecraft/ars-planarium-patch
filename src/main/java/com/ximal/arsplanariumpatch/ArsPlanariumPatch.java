package com.ximal.arsplanariumpatch;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(ArsPlanariumPatch.MODID)
public class ArsPlanariumPatch {
    public static final String MODID = "ars_planarium_patch";
    public static final Logger LOGGER = LogManager.getLogger("ArsPlanariumPatch");

    public static final DeferredRegister<MapCodec<? extends ChunkGenerator>> CHUNK_GENERATORS =
            DeferredRegister.create(Registries.CHUNK_GENERATOR, MODID);

    public static final DeferredHolder<MapCodec<? extends ChunkGenerator>, MapCodec<PlanariumGridChunkGenerator>> GRID_CHUNK_GENERATOR =
            CHUNK_GENERATORS.register("grid", PlanariumGridChunkGenerator::makeCodec);

    public ArsPlanariumPatch(IEventBus modBus, ModContainer container) {
        CHUNK_GENERATORS.register(modBus);
        container.registerConfig(ModConfig.Type.SERVER, Config.SERVER_SPEC);

        NeoForge.EVENT_BUS.addListener(ArsPlanariumPatch::onServerStarted);
        NeoForge.EVENT_BUS.addListener(LegacyDimensionCleanup::onServerTick);
    }

    private static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        PlanariumService.initServer(server);

        if (PlanariumDimension.level(server) == null) {
            LOGGER.error("Shared planarium dimension {} is missing! The datapack dimension did not load. "
                    + "Falling back to the legacy per-planarium dimensions.", PlanariumDimension.LEVEL.location());
        } else {
            LOGGER.info("Shared planarium dimension {} loaded", PlanariumDimension.LEVEL.location());
        }
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}
