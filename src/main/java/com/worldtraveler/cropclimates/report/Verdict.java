package com.worldtraveler.cropclimates.report;

import com.worldtraveler.cropclimates.CropClimates;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * The word a growth multiplier earns, shared by the Soil Tester, Jade, the
 * Alt tooltip and {@code /cropclimates explain} so they always agree.
 */
public enum Verdict {
    THRIVING(ChatFormatting.GREEN),
    BETTER(ChatFormatting.GREEN),
    NORMAL(ChatFormatting.WHITE),
    SLUGGISH(ChatFormatting.YELLOW),
    STRUGGLING(ChatFormatting.GOLD),
    BARELY_ALIVE(ChatFormatting.RED),
    DEAD(ChatFormatting.DARK_RED);

    private static final Verdict[] VALUES = values();

    public final ChatFormatting color;

    Verdict(ChatFormatting color) {
        this.color = color;
    }

    public static Verdict of(double total, double growthMax) {
        if (total >= growthMax * 0.92) {
            return THRIVING;
        }
        if (total >= 1.05) {
            return BETTER;
        }
        if (total >= 0.85) {
            return NORMAL;
        }
        if (total >= 0.5) {
            return SLUGGISH;
        }
        if (total >= 0.2) {
            return STRUGGLING;
        }
        if (total >= 0.06) {
            return BARELY_ALIVE;
        }
        return DEAD;
    }

    public static Verdict byId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : NORMAL;
    }

    private String key() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /** "thriving", "struggling", ... */
    public MutableComponent label() {
        return Component.translatable("verdict." + CropClimates.MOD_ID + "." + key()).withStyle(color);
    }

    /** "would thrive", "would struggle to survive", ... */
    public MutableComponent wouldLabel() {
        return Component.translatable("verdict." + CropClimates.MOD_ID + ".would." + key()).withStyle(color);
    }
}
