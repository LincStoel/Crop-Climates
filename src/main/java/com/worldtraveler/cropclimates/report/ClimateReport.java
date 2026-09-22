package com.worldtraveler.cropclimates.report;

import com.worldtraveler.cropclimates.CropClimates;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The boxed chat layout shared by the Soil Tester, the Hygrometer and
 * {@code /cropclimates explain}:
 * <pre>
 * ┌─ Title ──────────
 * │ row
 * ├──────────────────
 * │ row
 * └──────────────────
 * </pre>
 */
public final class ClimateReport {

    private static final String BORDER = "─".repeat(30);

    private final List<Component> lines = new ArrayList<>();

    public ClimateReport(Component title) {
        lines.add(Component.literal("┌─ ").withStyle(ChatFormatting.DARK_GRAY)
                .append(title.copy().withStyle(ChatFormatting.GOLD))
                .append(Component.literal(" " + BORDER).withStyle(ChatFormatting.DARK_GRAY)));
    }

    public static MutableComponent key(String path, Object... args) {
        return Component.translatable("report." + CropClimates.MOD_ID + "." + path, args);
    }

    /** A value inside a row, in white. */
    public static MutableComponent value(Object text) {
        return Component.literal(String.valueOf(text)).withStyle(ChatFormatting.WHITE);
    }

    public static MutableComponent pct(double v) {
        return value(Math.round(v * 100) + "%");
    }

    public static MutableComponent yesNo(boolean v) {
        return key(v ? "true" : "false").withStyle(v ? ChatFormatting.GREEN : ChatFormatting.RED);
    }

    /** {@code [ok]} / {@code [too low]} / {@code [too high]} for a value against a band. */
    public static MutableComponent mark(double v, double lo, double hi) {
        if (v >= lo && v <= hi) {
            return key("mark.ok").withStyle(ChatFormatting.GREEN);
        }
        return v < lo
                ? key("mark.too_low").withStyle(ChatFormatting.AQUA)
                : key("mark.too_high").withStyle(ChatFormatting.RED);
    }

    public ClimateReport row(Component text) {
        lines.add(Component.literal("│ ").withStyle(ChatFormatting.DARK_GRAY).append(text));
        return this;
    }

    /** A grey label row: {@code key(path, args)} styled grey, args keep their own style. */
    public ClimateReport row(String path, Object... args) {
        return row(key(path, args).withStyle(ChatFormatting.GRAY));
    }

    public ClimateReport divider() {
        lines.add(Component.literal("├" + BORDER).withStyle(ChatFormatting.DARK_GRAY));
        return this;
    }

    public void send(Consumer<Component> out) {
        lines.forEach(out);
        out.accept(Component.literal("└" + BORDER).withStyle(ChatFormatting.DARK_GRAY));
    }
}
