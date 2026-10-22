package com.ximal.arsplanariumpatch.mixin;

import com.hollingsworth.arsnouveau.common.block.tile.PlanariumTile;
import com.ximal.arsplanariumpatch.PlanariumDimension;
import com.ximal.arsplanariumpatch.PlanariumService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = PlanariumTile.class, priority = 500)
public abstract class PlanariumTileMixin {

    /**
     * Replaces the per-planarium dimension creation with a cell in the shared dimension.
     * Falls through to the original method if the shared dimension is unavailable.
     */
    @Inject(method = "setDimension", at = @At("HEAD"), cancellable = true)
    private void ars_planarium_patch$setDimension(String dimName, ServerLevel serverLevel,
                                                  CallbackInfoReturnable<ServerLevel> cir) {
        if (PlanariumDimension.level(serverLevel.getServer()) == null) {
            return;
        }
        cir.setReturnValue(PlanariumService.setDimension((PlanariumTile) (Object) this, dimName, serverLevel));
    }

    /**
     * Teleports into the cell of this planarium instead of into a dedicated dimension.
     * The method signature is intentionally unchanged: ars-sable wraps it with @WrapMethod.
     */
    @Inject(method = "sendEntityTo", at = @At("HEAD"), cancellable = true)
    private void ars_planarium_patch$sendEntityTo(Entity entity, CallbackInfo ci) {
        PlanariumTile tile = (PlanariumTile) (Object) this;
        if (!(tile.getLevel() instanceof ServerLevel serverLevel)
                || PlanariumDimension.level(serverLevel.getServer()) == null) {
            return;
        }
        PlanariumService.sendEntityTo(tile, entity);
        ci.cancel();
    }
}
