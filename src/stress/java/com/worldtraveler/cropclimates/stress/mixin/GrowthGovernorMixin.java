package com.worldtraveler.cropclimates.stress.mixin;

import com.worldtraveler.cropclimates.growth.GrowthGovernor;
import com.worldtraveler.cropclimates.stress.Counters;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = GrowthGovernor.class, remap = false)
public abstract class GrowthGovernorMixin {

    @Inject(method = "read", at = @At("RETURN"))
    private static void cc$read(Level level, BlockPos pos, BlockState state,
                                CallbackInfoReturnable<GrowthGovernor.GrowthReading> cir) {
        if (cir.getReturnValue() != null) {
            Counters.readsGoverned++;
        } else {
            Counters.readsUngoverned++;
        }
    }
}
