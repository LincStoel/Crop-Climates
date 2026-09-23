package com.worldtraveler.cropclimates.report;

import com.mojang.logging.LogUtils;
import com.worldtraveler.cropclimates.CropClimatesConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * The word a growth multiplier earns, shared by the Soil Tester, Jade, the
 * Alt tooltip, {@code /cropclimates explain} and the planting advancements so
 * they always agree. Each tier's threshold and names come from the
 * {@code [verdicts]} section of the server config; the client never reads that
 * config, so the server sends the names along with the tier.
 */
public enum Verdict {
    THRIVING(ChatFormatting.GREEN, "thriving", 0.92, "thriving", "would thrive"),
    BETTER(ChatFormatting.GREEN, "better", 0.84, "healthy growth", "would grow better than usual"),
    NORMAL(ChatFormatting.WHITE, "normal", 0.68, "about normal", "would grow about normally"),
    SLUGGISH(ChatFormatting.YELLOW, "sluggish", 0.40, "sluggish", "would grow sluggishly"),
    STRUGGLING(ChatFormatting.GOLD, "struggling", 0.16, "struggling", "would struggle"),
    BARELY_ALIVE(ChatFormatting.RED, "barelyAlive", 0.048, "barely alive", "would struggle to survive"),
    /** The bottom tier: everything below every threshold. It has no threshold of its own. */
    DEAD(ChatFormatting.DARK_RED, "dead", Double.NaN, "effectively dead", "would not survive");

    private static final Verdict[] VALUES = values();
    private static volatile boolean warnedOrder;

    public final ChatFormatting color;
    /** This tier's subsection under {@code [verdicts]} in the config. */
    public final String configKey;
    /** Default threshold as a fraction of growthMax; NaN for {@link #DEAD}. */
    public final double defaultThreshold;
    public final String defaultName;
    public final String defaultTooltipName;

    Verdict(ChatFormatting color, String configKey, double defaultThreshold, String defaultName, String defaultTooltipName) {
        this.color = color;
        this.configKey = configKey;
        this.defaultThreshold = defaultThreshold;
        this.defaultName = defaultName;
        this.defaultTooltipName = defaultTooltipName;
    }

    public boolean hasThreshold() {
        return !Double.isNaN(defaultThreshold);
    }

    /**
     * The first tier, from the top, whose {@code threshold * growthMax} the
     * multiplier reaches. Server side only.
     */
    public static Verdict of(double total, double growthMax) {
        warnIfMisordered();
        for (Verdict verdict : VALUES) {
            if (verdict.hasThreshold() && total >= CropClimatesConfig.verdictThreshold(verdict) * growthMax) {
                return verdict;
            }
        }
        return DEAD;
    }

    private static void warnIfMisordered() {
        if (warnedOrder) {
            return;
        }
        double previous = Double.POSITIVE_INFINITY;
        for (Verdict verdict : VALUES) {
            if (!verdict.hasThreshold()) {
                continue;
            }
            double threshold = CropClimatesConfig.verdictThreshold(verdict);
            if (threshold > previous) {
                warnedOrder = true;
                LogUtils.getLogger().warn("crop_climates: verdict thresholds should descend from thriving to barelyAlive; "
                        + "'{}' ({}) is above the tier before it, so it can only be reached where that tier is not", verdict.configKey, threshold);
                return;
            }
            previous = threshold;
        }
    }

    public static Verdict byId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : NORMAL;
    }

    /** "thriving", "struggling", ... Server side only. */
    public String labelText() {
        return CropClimatesConfig.verdictName(this);
    }

    /** "would thrive", "would struggle to survive", ... Server side only. */
    public String tooltipText() {
        return CropClimatesConfig.verdictTooltipName(this);
    }

    /** {@link #labelText()} in this tier's colour. Server side only. */
    public MutableComponent label() {
        return Component.literal(labelText()).withStyle(color);
    }

    /** {@link #tooltipText()} in this tier's colour. Server side only. */
    public MutableComponent tooltipLabel() {
        return Component.literal(tooltipText()).withStyle(color);
    }

    /** Client side: text the server resolved for tier {@code id}, in that tier's colour. */
    public static MutableComponent styled(int id, String text) {
        return Component.literal(text).withStyle(byId(id).color);
    }
}
