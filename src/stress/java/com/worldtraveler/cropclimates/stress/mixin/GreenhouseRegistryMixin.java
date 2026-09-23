package com.worldtraveler.cropclimates.stress.mixin;

import com.worldtraveler.cropclimates.greenhouse.GreenhouseRegistry;
import com.worldtraveler.cropclimates.greenhouse.RoomScan;
import com.worldtraveler.cropclimates.stress.Counters;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = GreenhouseRegistry.class, remap = false)
public abstract class GreenhouseRegistryMixin {

    @Unique
    private static long cc$tickStart;
    @Unique
    private static long cc$installStart;
    @Unique
    private static long cc$loadStart;

    @Inject(method = "tick", at = @At("HEAD"))
    private void cc$tickHead(ServerLevel level, CallbackInfo ci) {
        cc$tickStart = System.nanoTime();
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void cc$tickReturn(ServerLevel level, CallbackInfo ci) {
        long d = System.nanoTime() - cc$tickStart;
        Counters.registryTicks++;
        Counters.registryTickNanos += d;
        Counters.registryTickMaxNanos = Math.max(Counters.registryTickMaxNanos, d);
    }

    @Inject(method = "finish", at = @At("HEAD"))
    private void cc$finish(ServerLevel level, @Coerce Object probe, RoomScan scan, long now, CallbackInfo ci) {
        Counters.scansFinished[scan.status().ordinal()]++;
        Counters.cellsInFinishedScans += scan.visited();
    }

    // installRoom returns void on older code and whether anything changed once the reindex is
    // incremental; one pair of hooks for each, whichever exists.
    @Unique
    private static final String INSTALL = "installRoom(Lcom/worldtraveler/cropclimates/greenhouse/Probe;"
            + "Lcom/worldtraveler/cropclimates/greenhouse/RoomScan;J)";

    @Inject(method = INSTALL + "V", at = @At("HEAD"), require = 0)
    private void cc$installHead(@Coerce Object anchor, RoomScan scan, long now, CallbackInfo ci) {
        cc$installStart = System.nanoTime();
    }

    @Inject(method = INSTALL + "V", at = @At("RETURN"), require = 0)
    private void cc$installReturn(@Coerce Object anchor, RoomScan scan, long now, CallbackInfo ci) {
        cc$installDone();
    }

    @Inject(method = INSTALL + "Z", at = @At("HEAD"), require = 0)
    private void cc$installHeadZ(@Coerce Object anchor, RoomScan scan, long now, CallbackInfoReturnable<Boolean> cir) {
        cc$installStart = System.nanoTime();
    }

    @Inject(method = INSTALL + "Z", at = @At("RETURN"), require = 0)
    private void cc$installReturnZ(@Coerce Object anchor, RoomScan scan, long now, CallbackInfoReturnable<Boolean> cir) {
        cc$installDone();
    }

    @Unique
    private static void cc$installDone() {
        long d = System.nanoTime() - cc$installStart;
        Counters.installs++;
        Counters.installNanos += d;
        Counters.installMaxNanos = Math.max(Counters.installMaxNanos, d);
    }

    @Inject(method = "matters", at = @At("RETURN"))
    private static void cc$matters(BlockState oldState, BlockState newState, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            Counters.mattersTrue++;
            Counters.rescanCauses.merge(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(oldState.getBlock()).getPath()
                    + " -> " + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(newState.getBlock()).getPath(), 1L, Long::sum);
        } else {
            Counters.mattersFalse++;
        }
    }

    @Inject(method = "load", at = @At("HEAD"))
    private static void cc$loadHead(CompoundTag tag, HolderLookup.Provider registries,
                                    CallbackInfoReturnable<GreenhouseRegistry> cir) {
        cc$loadStart = System.nanoTime();
    }

    @Inject(method = "load", at = @At("RETURN"))
    private static void cc$loadReturn(CompoundTag tag, HolderLookup.Provider registries,
                                      CallbackInfoReturnable<GreenhouseRegistry> cir) {
        Counters.loads++;
        Counters.loadNanos += System.nanoTime() - cc$loadStart;
    }

    @Inject(method = "onBlockChanged", at = @At("HEAD"))
    private void cc$blockChanged(BlockPos pos, BlockState oldState, BlockState newState, long now, CallbackInfo ci) {
        Counters.blockChanges++;
    }
}
