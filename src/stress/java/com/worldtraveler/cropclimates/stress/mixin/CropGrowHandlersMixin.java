package com.worldtraveler.cropclimates.stress.mixin;

import com.worldtraveler.cropclimates.growth.CropGrowHandlers;
import com.worldtraveler.cropclimates.stress.Counters;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.level.block.CropGrowEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = CropGrowHandlers.class, remap = false)
public abstract class CropGrowHandlersMixin {

    @Inject(method = "onPre", at = @At("HEAD"))
    private static void cc$pre(CropGrowEvent.Pre event, CallbackInfo ci) {
        Counters.preCalls++;
    }

    @Inject(method = "onPost", at = @At("HEAD"))
    private static void cc$post(CropGrowEvent.Post event, CallbackInfo ci) {
        Counters.postCalls++;
    }

    @Inject(method = "extraTick", at = @At("HEAD"))
    private static void cc$extra(ServerLevel level, BlockPos pos, CallbackInfo ci) {
        if (!CropGrowHandlers.isForcing()) {
            Counters.extraTicks++;
        }
    }
}
