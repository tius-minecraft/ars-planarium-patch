package com.ximal.arsplanariumpatch.mixin;

import com.hollingsworth.arsnouveau.common.block.tile.PlanariumTile;
import com.hollingsworth.arsnouveau.common.network.Networking;
import com.hollingsworth.arsnouveau.common.network.PacketClientRequestDim;
import com.hollingsworth.arsnouveau.common.network.PacketUpdateDimTile;
import com.ximal.arsplanariumpatch.PlanariumDimension;
import com.ximal.arsplanariumpatch.PlanariumService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PacketClientRequestDim.class)
public abstract class PacketClientRequestDimMixin {

    @Shadow
    public BlockPos pos;

    /**
     * A client that lost or never received its template asks for a snapshot. In the shared dimension
     * the server-side cache can be stale (or missing) even though the cell already contains the
     * build, so do not trust {@code PlanariumTile.dimManager.getTemplate} here: load a fresh
     * snapshot from the cell before answering.
     */
    @Inject(method = "onServerReceived", at = @At("HEAD"), cancellable = true)
    private void ars_planarium_patch$onServerReceived(MinecraftServer server, ServerPlayer player, CallbackInfo ci) {
        if (!(player.level() instanceof ServerLevel level) || PlanariumDimension.level(server) == null) {
            return;
        }
        if (!(level.getBlockEntity(pos) instanceof PlanariumTile tile) || tile.key == null) {
            return;
        }

        PlanariumTile.DimManager.Entry entry = PlanariumService.getOrCreateTemplate(level, pos, tile.key);
        if (entry != null && entry.template() != null) {
            Networking.sendToPlayerClient(new PacketUpdateDimTile(pos, entry.template()), player);
            PlanariumService.clearPendingRefresh(tile.key);
        }
        ci.cancel();
    }
}
