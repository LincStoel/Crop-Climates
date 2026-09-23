package com.worldtraveler.cropclimates.stress.mixin;

import com.worldtraveler.cropclimates.climate.ClimateSampler;
import com.worldtraveler.cropclimates.stress.Counters;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.OptionalDouble;

@Mixin(value = ClimateSampler.class, remap = false)
public abstract class ClimateSamplerMixin {

    @Inject(method = "temperatureF", at = @At("HEAD"))
    private static void cc$calls(Level level, BlockPos pos, CallbackInfoReturnable<OptionalDouble> cir) {
        Counters.tempCalls++;
    }

    @Inject(method = "temperatureF", at = @At(value = "INVOKE",
            target = "Lcom/momosoftworks/coldsweat/util/world/WorldHelper;getRoughTemperatureAt(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)D"))
    private static void cc$fresh(Level level, BlockPos pos, CallbackInfoReturnable<OptionalDouble> cir) {
        Counters.tempFreshReads++;
    }

    @Inject(method = "temperatureF", at = @At(value = "INVOKE", target = "Ljava/util/concurrent/ConcurrentHashMap;clear()V"))
    private static void cc$wipe(Level level, BlockPos pos, CallbackInfoReturnable<OptionalDouble> cir) {
        Counters.tempWipes++;
    }

    @Inject(method = "temperatureF", at = @At("RETURN"))
    private static void cc$result(Level level, BlockPos pos, CallbackInfoReturnable<OptionalDouble> cir) {
        if (cir.getReturnValue().isEmpty()) {
            Counters.tempUnavailable++;
        }
    }

    @Inject(method = "withinBudget", at = @At("RETURN"))
    private static void cc$budget(long now, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) {
            Counters.tempDenied++;
        }
    }
}
