package com.worldtraveler.cropclimates.climate;

import com.momosoftworks.coldsweat.api.util.Temperature;
import com.momosoftworks.coldsweat.common.entity.data.Preference;
import com.momosoftworks.coldsweat.config.ConfigSettings;
import com.mojang.logging.LogUtils;
import com.worldtraveler.cropclimates.CropClimatesConfig;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;

import javax.annotation.Nullable;

/**
 * Which unit to show temperatures in. Bands and tolerances stay Fahrenheit
 * internally; only display converts.
 *
 * <p>Cold Sweat syncs each player's own unit choice to the server
 * ({@link Preference#UNITS}), so server-built reports can match it. If that
 * ever fails, reports fall back to the {@code displayUnits} server option,
 * logged once.
 */
public final class TemperatureUnits {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile boolean preferenceAvailable = true;
    private static volatile boolean clientAvailable = true;

    private TemperatureUnits() {
    }

    /** Server side: the player's Cold Sweat unit, else the server's {@code displayUnits}. */
    public static CropClimatesConfig.Units forPlayer(@Nullable Player player) {
        if (player != null && preferenceAvailable) {
            try {
                Temperature.Units units = Preference.getOrDefault(player, Preference.UNITS, null);
                CropClimatesConfig.Units mapped = map(units);
                if (mapped != null) {
                    return mapped;
                }
            } catch (RuntimeException | LinkageError ex) {
                preferenceAvailable = false;
                LOGGER.warn("crop_climates: could not read Cold Sweat unit preferences, using displayUnits", ex);
            }
        }
        return CropClimatesConfig.DISPLAY_UNITS.get();
    }

    /** Client side: Cold Sweat's own client unit setting, defaulting to Fahrenheit. */
    public static CropClimatesConfig.Units forClient() {
        if (clientAvailable) {
            try {
                CropClimatesConfig.Units mapped = map(ConfigSettings.UNITS.get());
                if (mapped != null) {
                    return mapped;
                }
            } catch (RuntimeException | LinkageError ex) {
                clientAvailable = false;
                LOGGER.warn("crop_climates: could not read Cold Sweat's unit setting, showing Fahrenheit", ex);
            }
        }
        return CropClimatesConfig.Units.F;
    }

    @Nullable
    private static CropClimatesConfig.Units map(@Nullable Temperature.Units units) {
        if (units == Temperature.Units.C) {
            return CropClimatesConfig.Units.C;
        }
        if (units == Temperature.Units.F) {
            return CropClimatesConfig.Units.F;
        }
        return null;
    }

    public static double fromFahrenheit(double fahrenheit, CropClimatesConfig.Units units) {
        return units == CropClimatesConfig.Units.C ? (fahrenheit - 32.0) * 5.0 / 9.0 : fahrenheit;
    }

    /** "72°F" / "22°C", rounded. */
    public static String format(double fahrenheit, CropClimatesConfig.Units units) {
        return Math.round(fromFahrenheit(fahrenheit, units)) + symbol(units);
    }

    /** "98.2°F" / "36.8°C", one decimal. */
    public static String formatPrecise(double fahrenheit, CropClimatesConfig.Units units) {
        return String.format(java.util.Locale.ROOT, "%.1f", fromFahrenheit(fahrenheit, units)) + symbol(units);
    }

    /** "50–80°F" / "10–27°C", rounded. */
    public static String formatRange(double loF, double hiF, CropClimatesConfig.Units units) {
        return Math.round(fromFahrenheit(loF, units)) + "–" + Math.round(fromFahrenheit(hiF, units)) + symbol(units);
    }

    private static String symbol(CropClimatesConfig.Units units) {
        return units == CropClimatesConfig.Units.C ? "°C" : "°F";
    }
}
