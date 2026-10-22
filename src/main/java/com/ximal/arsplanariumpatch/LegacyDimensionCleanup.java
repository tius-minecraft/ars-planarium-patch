package com.ximal.arsplanariumpatch;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.List;

/**
 * Unregisters the old per-planarium dimensions after their contents have been migrated.
 *
 * <p>A dimension is only unregistered when no player is inside it. The pending state is persisted
 * in {@link PlanariumLayoutData}, so cleanup resumes after a restart.</p>
 */
public final class LegacyDimensionCleanup {
    private static final int INTERVAL_TICKS = 100;

    private LegacyDimensionCleanup() {
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % INTERVAL_TICKS != 0) {
            return;
        }

        PlanariumLayoutData layout = PlanariumLayoutData.from(server);
        for (PlanariumLayoutData.Entry entry : List.copyOf(layout.entries())) {
            if (!entry.legacy()) {
                continue;
            }

            ServerLevel oldLevel = server.getLevel(entry.dimensionKey());
            if (oldLevel == null) {
                // Already unregistered (or never existed).
                layout.setLegacy(entry, false);
                continue;
            }
            if (!oldLevel.players().isEmpty()) {
                continue;
            }

            if (InfiniverseCompat.unregisterDimension(server, oldLevel.dimension())) {
                layout.setLegacy(entry, false);
                ArsPlanariumPatch.LOGGER.info("Unregistered legacy planarium dimension {} for '{}'",
                        oldLevel.dimension().location(), entry.name());
            }
        }
    }
}
