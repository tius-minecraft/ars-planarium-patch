package com.ximal.arsplanariumpatch.mixin;

import com.hollingsworth.arsnouveau.common.block.tile.PlanariumTile;
import com.ximal.arsplanariumpatch.PlanariumDimension;
import com.ximal.arsplanariumpatch.PlanariumService;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlanariumTile.DimManager.class)
public abstract class PlanariumDimManagerMixin {

    /**
     * Loads the template snapshot from the shared cell instead of the planarium's own dimension.
     */
    @Inject(method = "getOrCreateTemplate", at = @At("HEAD"), cancellable = true)
    private void ars_planarium_patch$getOrCreateTemplate(ServerLevel serverLevel, BlockPos worldPosition,
                                                         ResourceKey<Level> key,
                                                         CallbackInfoReturnable<PlanariumTile.DimManager.Entry> cir) {
        if (PlanariumDimension.level(serverLevel.getServer()) == null) {
            return;
        }
        cir.setReturnValue(PlanariumService.getOrCreateTemplate(serverLevel, worldPosition, key));
    }

    @Inject(method = "onBlockBroken", at = @At("HEAD"), cancellable = true)
    private static void ars_planarium_patch$onBlockBroken(BlockEvent.BreakEvent event, CallbackInfo ci) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || PlanariumDimension.level(level.getServer()) == null) {
            return;
        }
        PlanariumService.onBlockBroken(event);
        ci.cancel();
    }

    @Inject(method = "onBlockPlaced", at = @At("HEAD"), cancellable = true)
    private static void ars_planarium_patch$onBlockPlaced(BlockEvent.EntityPlaceEvent event, CallbackInfo ci) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || PlanariumDimension.level(level.getServer()) == null) {
            return;
        }
        PlanariumService.onBlockPlaced(event);
        ci.cancel();
    }
}
