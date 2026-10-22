package com.ximal.arsplanariumpatch.mixin;

import com.hollingsworth.arsnouveau.api.item.IWandable;
import com.hollingsworth.arsnouveau.common.block.DimBoundary;
import com.ximal.arsplanariumpatch.PlanariumDimension;
import com.ximal.arsplanariumpatch.PlanariumService;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(DimBoundary.class)
public abstract class DimBoundaryMixin {

    /**
     * In the shared dimension the spawn point must be resolved against the cell the player clicked in.
     * Legacy per-planarium dimensions keep the original behaviour.
     */
    @Inject(method = "onFirstConnection", at = @At("HEAD"), cancellable = true)
    private void ars_planarium_patch$onFirstConnection(@Nullable GlobalPos storedPos, @Nullable Direction face,
                                                       @Nullable LivingEntity storedEntity, Player playerEntity,
                                                       CallbackInfoReturnable<IWandable.Result> cir) {
        Level level = playerEntity.level();
        if (!PlanariumDimension.isShared(level)) {
            return;
        }
        cir.setReturnValue(PlanariumService.onFirstConnection(storedPos, level, playerEntity));
    }
}
