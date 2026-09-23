package com.worldtraveler.cropclimates.stress.mixin;

import com.worldtraveler.cropclimates.greenhouse.RoomScan;
import com.worldtraveler.cropclimates.stress.Counters;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = RoomScan.class, remap = false)
public abstract class RoomScanMixin {

    @Shadow
    private int visited;

    @Unique
    private int cc$visitedAtStep;

    @Inject(method = "step", at = @At("HEAD"))
    private void cc$stepHead(int budget, CallbackInfoReturnable<RoomScan.Status> cir) {
        cc$visitedAtStep = visited;
    }

    @Inject(method = "step", at = @At("RETURN"))
    private void cc$stepReturn(int budget, CallbackInfoReturnable<RoomScan.Status> cir) {
        int stepped = visited - cc$visitedAtStep;
        Counters.cellsScanned += stepped;
        Counters.cellsThisTick += stepped;
    }
}
