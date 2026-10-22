package com.ximal.arsplanariumpatch;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

/**
 * Infiniverse is jar-in-jar'd by Ars Nouveau, so it is not a direct compile dependency.
 * The only operation we need is dimension unregistration, which we invoke reflectively.
 */
final class InfiniverseCompat {
    private static final String DIMENSION_MANAGER = "net.commoble.infiniverse.internal.DimensionManager";

    private InfiniverseCompat() {
    }

    static boolean unregisterDimension(MinecraftServer server, ResourceKey<Level> dimension) {
        try {
            Class<?> clazz = Class.forName(DIMENSION_MANAGER);
            Object instance = clazz.getField("INSTANCE").get(null);
            clazz.getMethod("markDimensionForUnregistration", MinecraftServer.class, ResourceKey.class)
                    .invoke(instance, server, dimension);
            return true;
        } catch (Throwable t) {
            ArsPlanariumPatch.LOGGER.warn("Could not unregister legacy planarium dimension {}: {}",
                    dimension.location(), t.toString());
            return false;
        }
    }
}
