package com.worldtraveler.cropclimates.stress.mixin;

import com.worldtraveler.cropclimates.growth.GrowthGovernor;
import com.worldtraveler.cropclimates.growth.Regression;
import com.worldtraveler.cropclimates.stress.Counters;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Regression.class, remap = false)
public abstract class RegressionMixin {

    /** Every veto reaches consider(): CropGrowEvent.Pre and the randomTick mixin both call it. */
    @Inject(method = "consider", at = @At("HEAD"))
    private static void cc$veto(ServerLevel level, BlockPos pos, BlockState state,
                                GrowthGovernor.GrowthReading reading, CallbackInfo ci) {
        Counters.vetoes++;
    }

    @Inject(method = "flush", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private static void cc$applied(ServerLevel level, CallbackInfo ci) {
        Counters.regressionsApplied++;
    }
}
