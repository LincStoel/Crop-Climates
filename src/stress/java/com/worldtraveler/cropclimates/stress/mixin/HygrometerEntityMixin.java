package com.worldtraveler.cropclimates.stress.mixin;

import com.worldtraveler.cropclimates.entity.HygrometerEntity;
import com.worldtraveler.cropclimates.stress.Counters;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = HygrometerEntity.class, remap = false)
public abstract class HygrometerEntityMixin {

    @Inject(method = "onNeighborNotify", at = @At("HEAD"))
    private static void cc$notify(BlockEvent.NeighborNotifyEvent event, CallbackInfo ci) {
        Counters.neighborNotifies++;
    }
}
