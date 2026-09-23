package com.worldtraveler.cropclimates.stress.mixin;

import com.worldtraveler.cropclimates.greenhouse.Room;
import com.worldtraveler.cropclimates.stress.Counters;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "com.worldtraveler.cropclimates.greenhouse.GreenhouseParticles", remap = false)
public abstract class GreenhouseParticlesMixin {

    @Inject(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;sendParticles(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I"))
    private static void cc$particle(ServerLevel level, Room room, CallbackInfo ci) {
        Counters.particlePackets++;
    }
}
