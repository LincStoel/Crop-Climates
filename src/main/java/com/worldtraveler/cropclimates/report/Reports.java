package com.worldtraveler.cropclimates.report;

import com.worldtraveler.cropclimates.CropClimatesConfig;
import com.worldtraveler.cropclimates.climate.ClimateBand;
import com.worldtraveler.cropclimates.climate.ClimateBands;
import com.worldtraveler.cropclimates.climate.ClimateSampler;
import com.worldtraveler.cropclimates.climate.EnclosureHumidity;
import com.worldtraveler.cropclimates.climate.HumiditySource;
import com.worldtraveler.cropclimates.climate.TemperatureUnits;
import com.worldtraveler.cropclimates.greenhouse.GreenhouseStatus;
import com.worldtraveler.cropclimates.greenhouse.Greenhouses;
import com.worldtraveler.cropclimates.greenhouse.Room;
import com.worldtraveler.cropclimates.growth.GrowthGovernor;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.OptionalDouble;

/**
 * Builds the chat reports. Every number comes from the same
 * {@link GrowthGovernor} and greenhouse registry the crops use, so a report
 * can never disagree with actual growth.
 */
public final class Reports {

    private Reports() {
    }

    /**
     * The Soil Tester / {@code /cropclimates explain} report for {@code pos}:
     * local climate, and for a governed plant its bands and multiplier.
     */
    public static ClimateReport climate(Level level, BlockPos clicked, Component title, CropClimatesConfig.Units units) {
        ClimateReport report = new ClimateReport(title);
        BlockPos pos = GrowthGovernor.growingEnd(level, clicked);
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();
        ClimateBand band = ClimateBands.bandFor(block);
        GrowthGovernor.GrowthReading reading = band == null ? null : GrowthGovernor.read(level, pos, state);

        if (reading != null && reading.waiver() == GrowthGovernor.HumidityWaiver.SUBMERGED) {
            submerged(report, level, pos, block, band, reading, units);
            return report;
        }

        MutableComponent sunlight = ClimateReport.key("sunlight", ClimateReport.sun(level.canSeeSky(pos), band != null && band.nether()))
                .withStyle(ChatFormatting.GRAY);
        OptionalDouble tempF = ClimateSampler.temperatureF(level, pos);

        if (tempF.isEmpty()) {
            report.row(biomeName(level, pos));
            report.row(ClimateReport.key("temp_unavailable").withStyle(ChatFormatting.GRAY).append("  ").append(sunlight));
            return report;
        }

        Room room = Greenhouses.roomAt(level, pos);
        HumiditySource.Outdoor outdoor = HumiditySource.outdoor(level, pos);
        double humidity = room != null ? room.humidity(level) : outdoor.humidity();
        boolean raining = room == null && outdoor.raining();
        MutableComponent humidityText = band != null
                ? ClimateReport.pct(humidity, band.moistLo(), band.moistHi(), raining)
                : ClimateReport.pct(humidity, raining);

        report.row(room != null
                ? ClimateReport.key("enclosed").withStyle(ChatFormatting.GREEN)
                : biomeName(level, pos));
        String tempText = band != null
                ? TemperatureUnits.format(tempF.getAsDouble(), band.tempLo(), band.tempHi(), units)
                : TemperatureUnits.format(tempF.getAsDouble(), units);
        report.row(ClimateReport.key("conditions", ClimateReport.value(tempText), humidityText)
                .withStyle(ChatFormatting.GRAY).append("  ").append(sunlight));

        if (reading == null) {
            return report;
        }
        GrowthGovernor.Conditions c = reading.conditions();

        report.divider();
        report.row(block.getName().withStyle(ChatFormatting.YELLOW));
        report.row("wants_temp", ClimateReport.value(TemperatureUnits.formatRange(band.tempLo(), band.tempHi(), units)),
                ClimateReport.mark(c.tempF(), band.tempLo(), band.tempHi()));
        report.row("wants_humidity",
                ClimateReport.value(Math.round(band.moistLo() * 100) + "–" + Math.round(band.moistHi() * 100) + "%"),
                ClimateReport.mark(c.humidity(), band.moistLo(), band.moistHi()));

        report.divider();
        growthRow(report, reading.total());
        return report;
    }

    /**
     * An underwater crop: scored on the air temperature like any other, but
     * neither humidity nor sunlight is held against it.
     */
    private static void submerged(ClimateReport report, Level level, BlockPos pos, Block block, ClimateBand band,
                                  GrowthGovernor.GrowthReading reading, CropClimatesConfig.Units units) {
        GrowthGovernor.Conditions c = reading.conditions();
        report.row(biomeName(level, pos));
        report.row(ClimateReport.key("conditions",
                ClimateReport.value(TemperatureUnits.format(c.tempF(), band.tempLo(), band.tempHi(), units)),
                ClimateReport.pct(c.humidity()))
                .withStyle(ChatFormatting.GRAY));

        report.divider();
        report.row(block.getName().withStyle(ChatFormatting.YELLOW));
        report.row("wants_temp",
                ClimateReport.value(TemperatureUnits.formatRange(band.tempLo(), band.tempHi(), units)),
                ClimateReport.mark(c.tempF(), band.tempLo(), band.tempHi()));

        report.divider();
        growthRow(report, reading.total());
    }

    /** The biome's display name, falling back to its id for biomes without a translation. */
    private static MutableComponent biomeName(Level level, BlockPos pos) {
        return level.getBiome(pos).unwrapKey()
                .map(k -> Component.translatableWithFallback(Util.makeDescriptionId("biome", k.location()),
                        k.location().toString()))
                .orElseGet(() -> Component.literal("unknown"))
                .withStyle(ChatFormatting.WHITE);
    }

    private static void growthRow(ClimateReport report, double total) {
        double growthMax = CropClimatesConfig.GROWTH_MAX.get();
        Verdict verdict = Verdict.of(total, growthMax);
        report.row("growth",
                Component.literal(String.format(Locale.ROOT, "%.2f", total)).withStyle(verdict.color),
                bar(total, growthMax, verdict.color),
                verdict.label());
    }

    /**
     * A hygrometer's report: greenhouse status, the humidity it reads, and for
     * a greenhouse what is inside it.
     */
    public static ClimateReport hygrometer(Level level, BlockPos pos, GreenhouseStatus status, @Nullable Room room,
                                           CropClimatesConfig.Units units) {
        ClimateReport report = new ClimateReport(ClimateReport.key("hygrometer"));
        boolean greenhouse = status == GreenhouseStatus.GREENHOUSE && room != null;
        BlockPos biomePos = greenhouse ? room.anchorPos() : pos;

        OptionalDouble tempF = ClimateSampler.temperatureF(level, pos);
        MutableComponent humidityText;
        if (greenhouse) {
            humidityText = ClimateReport.pct(room.humidity(level));
        } else {
            HumiditySource.Outdoor outdoor = HumiditySource.outdoor(level, pos);
            humidityText = ClimateReport.pct(outdoor.humidity(), outdoor.raining());
        }

        MutableComponent conditions = (tempF.isPresent()
                ? ClimateReport.key("conditions", ClimateReport.value(TemperatureUnits.format(tempF.getAsDouble(), units)), humidityText)
                : ClimateReport.key("hygrometer.humidity", humidityText)).withStyle(ChatFormatting.GRAY);

        MutableComponent biomeRow = biomeName(level, biomePos);
        if (greenhouse) {
            biomeRow = biomeRow.append("   ").append(ClimateReport.key("hygrometer.base",
                    ClimateReport.pct(room.baseHumidity(level))).withStyle(ChatFormatting.GRAY));
        } else {
            biomeRow = biomeRow.append("   ").append(conditions);
        }
        report.row(biomeRow);
        if (greenhouse) {
            report.divider();
        }

        switch (status) {
            case GREENHOUSE -> report.row(ClimateReport.key("hygrometer.greenhouse").withStyle(ChatFormatting.GREEN));
            case OUTDOOR -> report.row(ClimateReport.key("hygrometer.outdoor").withStyle(ChatFormatting.YELLOW));
            case TOO_SMALL -> report.row(ClimateReport.key("hygrometer.too_small",
                    ClimateReport.value(CropClimatesConfig.GREENHOUSE_MIN_VOLUME.get())).withStyle(ChatFormatting.YELLOW));
            case TOO_LARGE -> {
                report.row(ClimateReport.key("hygrometer.too_large",
                        ClimateReport.value(CropClimatesConfig.greenhouseMaxVolume())).withStyle(ChatFormatting.YELLOW));
                report.row(ClimateReport.key("hygrometer.rescan_hint").withStyle(ChatFormatting.GRAY));
            }
            case SCANNING -> report.row(ClimateReport.key("hygrometer.scanning").withStyle(ChatFormatting.GRAY));
            case DISABLED -> report.row(ClimateReport.key("hygrometer.disabled").withStyle(ChatFormatting.GRAY));
        }

        if (greenhouse) {
            report.row(conditions);
            report.divider();
            report.row("hygrometer.size", ClimateReport.value(room.size()));
            report.row("hygrometer.sources",
                    ClimateReport.value(room.sourceCount(EnclosureHumidity.Effect.WATER)),
                    ClimateReport.value(room.sourceCount(EnclosureHumidity.Effect.HUMIDIFIER)),
                    ClimateReport.value(room.sourceCount(EnclosureHumidity.Effect.DESICCANT)),
                    ClimateReport.value(room.sourceCount(EnclosureHumidity.Effect.LAVA)));
        }
        return report;
    }

    /** {@code [||||||....]}: the filled part in the verdict colour, the rest dark grey. */
    private static MutableComponent bar(double total, double growthMax, ChatFormatting color) {
        int filled = (int) Math.round((Math.min(total, growthMax) / growthMax) * 10);
        return Component.literal("[").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal("|".repeat(filled)).withStyle(color))
                .append(Component.literal(".".repeat(10 - filled)).withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal("]").withStyle(ChatFormatting.DARK_GRAY));
    }
}
