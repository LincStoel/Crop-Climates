package com.worldtraveler.cropclimates.stress.mixin;

import com.worldtraveler.cropclimates.growth.GrowthGovernor;
import com.worldtraveler.cropclimates.growth.Regression;
import com.worldtraveler.cropclimates.stress.Counters;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
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

    @Unique
    private static final String SET_BLOCK =
            "Lnet/minecraft/server/level/ServerLevel;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z";

    // The wilt's setBlock sits in flush() on older code and in apply() once each wilt is guarded.
    @Inject(method = "flush", at = @At(value = "INVOKE", target = SET_BLOCK), require = 0)
    private static void cc$applied(ServerLevel level, CallbackInfo ci) {
        Counters.regressionsApplied++;
    }

    @Inject(method = "apply", at = @At(value = "INVOKE", target = SET_BLOCK), require = 0)
    private static void cc$appliedGuarded(ServerLevel level, @Coerce Object wilt, CallbackInfo ci) {
        Counters.regressionsApplied++;
    }
}
