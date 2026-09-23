package com.worldtraveler.cropclimates.stress.mixin;

import com.worldtraveler.cropclimates.greenhouse.GreenhouseRegistry;
import com.worldtraveler.cropclimates.stress.Counters;
import net.minecraft.world.level.saveddata.SavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;

/**
 * Times the whole server-thread part of saving the greenhouse registry:
 * NeoForge's {@code SavedData.save(File, Provider)} builds the tag, deep
 * copies it and only then hands the write to an IO worker.
 */
@Mixin(value = SavedData.class)
public abstract class GreenhousesMixin {

    @Unique
    private long cc$saveStart;

    @Inject(method = "save(Ljava/io/File;Lnet/minecraft/core/HolderLookup$Provider;)V", at = @At("HEAD"))
    private void cc$saveHead(File file, net.minecraft.core.HolderLookup.Provider registries, CallbackInfo ci) {
        if ((Object) this instanceof GreenhouseRegistry && ((SavedData) (Object) this).isDirty()) {
            cc$saveStart = System.nanoTime();
        } else {
            cc$saveStart = 0;
        }
    }

    @Inject(method = "save(Ljava/io/File;Lnet/minecraft/core/HolderLookup$Provider;)V", at = @At("RETURN"))
    private void cc$saveReturn(File file, net.minecraft.core.HolderLookup.Provider registries, CallbackInfo ci) {
        if (cc$saveStart != 0) {
            long d = System.nanoTime() - cc$saveStart;
            Counters.saves++;
            Counters.saveNanos += d;
            Counters.saveMaxNanos = Math.max(Counters.saveMaxNanos, d);
        }
    }
}
