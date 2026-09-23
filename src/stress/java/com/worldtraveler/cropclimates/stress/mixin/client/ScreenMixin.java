package com.worldtraveler.cropclimates.stress.mixin.client;

import com.worldtraveler.cropclimates.stress.client.ClientProbe;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Lets the harness render the Alt-gated tooltip without a real key press. */
@Mixin(Screen.class)
public abstract class ScreenMixin {

    @Inject(method = "hasAltDown", at = @At("HEAD"), cancellable = true)
    private static void cc$forceAlt(CallbackInfoReturnable<Boolean> cir) {
        if (ClientProbe.forceAlt) {
            cir.setReturnValue(true);
        }
    }
}
