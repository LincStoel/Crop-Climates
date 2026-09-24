package com.worldtraveler.cropclimates.report;

import com.worldtraveler.cropclimates.CropClimates;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    private static final int BORDER_LENGTH = 30;
    private static final String BORDER = "─".repeat(BORDER_LENGTH);

    private final List<Component> lines = new ArrayList<>();

    public ClimateReport(Component title) {
        // The title eats into the border so the top line is no wider than the
        // dividers and doesn't wrap. Letters run about half the width of a
        // box-drawing dash on the default font; the server resolves the title
        // in en_us, which is close enough for other languages.
        int titleDashes = title.getString().length() / 2;
        int tail = Math.max(3, BORDER_LENGTH - 3 - titleDashes);
        lines.add(Component.literal("┌─ ").withStyle(ChatFormatting.DARK_GRAY)
                .append(title.copy().withStyle(ChatFormatting.GOLD))
                .append(Component.literal(" " + "─".repeat(tail)).withStyle(ChatFormatting.DARK_GRAY)));
    }

    public static MutableComponent key(String path, Object... args) {
        return Component.translatable("report." + CropClimates.MOD_ID + "." + path, args);
    }

    /** A value inside a row, in white. */
    public static MutableComponent value(Object text) {
        return Component.literal(String.valueOf(text)).withStyle(ChatFormatting.WHITE);
    }

    public static MutableComponent pct(double v) {
        return pct(Math.round(v * 100) + "%", false);
    }

    /** {@link #pct(double)}, blue with a trailing 🌧 while it's raining. */
    public static MutableComponent pct(double v, boolean raining) {
        return pct(Math.round(v * 100) + "%", raining);
    }

    /**
     * {@link #pct(double, boolean)}, with a decimal if rounding would hide that
     * {@code v} is outside {@code lo–hi}.
     */
    public static MutableComponent pct(double v, double lo, double hi, boolean raining) {
        return pct(nearEdge(v * 100, lo * 100, hi * 100) + "%", raining);
    }

    private static MutableComponent pct(String text, boolean raining) {
        MutableComponent c = Component.literal(text).withStyle(raining ? ChatFormatting.BLUE : ChatFormatting.WHITE);
        return raining ? c.append(" 🌧") : c;
    }

    /**
     * {@code v} as a whole number, unless it is outside {@code lo–hi} but
     * would round to a number inside it - "71" beside "wants 71–91 ✘ too low"
     * reads as a contradiction. Then it gets one decimal, rounded away from
     * the band ("70.6"), so the shown number is always on the side the mark
     * says. All three arguments are in display units.
     */
    public static String nearEdge(double v, double lo, double hi) {
        long rounded = Math.round(v);
        boolean looksInside = rounded >= Math.round(lo) && rounded <= Math.round(hi);
        if (looksInside && v < lo) {
            return String.format(Locale.ROOT, "%.1f", Math.floor(v * 10 + 1e-9) / 10);
        }
        if (looksInside && v > hi) {
            return String.format(Locale.ROOT, "%.1f", Math.ceil(v * 10 - 1e-9) / 10);
        }
        return Long.toString(rounded);
    }

    /** A ☀️ in sunlight - yellow, or red for a nether crop it harms - and a gray ◯ without. */
    public static MutableComponent sun(boolean v, boolean nether) {
        ChatFormatting color = !v ? ChatFormatting.GRAY : nether ? ChatFormatting.RED : ChatFormatting.YELLOW;
        return key(v ? "mark.sun" : "mark.no_sun").withStyle(color);
    }

    /** A tick, {@code ✘ too low} or {@code ✘ too high} for a value against a band. */
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
