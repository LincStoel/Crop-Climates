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
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
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
    public static ClimateReport climate(Level level, BlockPos pos, Component title, CropClimatesConfig.Units units) {
        ClimateReport report = new ClimateReport(title);
        String biomeId = level.getBiome(pos).unwrapKey().map(k -> k.location().toString()).orElse("unknown");
        boolean seesSky = level.canSeeSky(pos);
        OptionalDouble tempF = ClimateSampler.temperatureF(level, pos);

        if (tempF.isEmpty()) {
            report.row(ClimateReport.value(biomeId));
            report.row("temp_unavailable");
            report.row("sky", ClimateReport.yesNo(seesSky));
            return report;
        }

        Room room = Greenhouses.roomAt(level, pos);
        HumiditySource.Outdoor outdoor = HumiditySource.outdoor(level, pos);
        double humidity = room != null ? room.humidity(level) : outdoor.humidity();
        MutableComponent humidityText = ClimateReport.pct(humidity);
        if (room == null && outdoor.raining()) {
            humidityText.append(ClimateReport.key("raining").withStyle(ChatFormatting.AQUA));
        }

        report.row(ClimateReport.value(biomeId).append(Component.literal("   "))
                .append(ClimateReport.key("sky", ClimateReport.yesNo(seesSky)).withStyle(ChatFormatting.GRAY)));
        report.row("temp_humidity", ClimateReport.value(TemperatureUnits.format(tempF.getAsDouble(), units)), humidityText);

        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();
        ClimateBand band = ClimateBands.bandFor(block);
        if (band == null) {
            return report;
        }
        GrowthGovernor.GrowthReading reading = GrowthGovernor.read(level, pos, state);
        if (reading == null) {
            return report;
        }
        GrowthGovernor.Conditions c = reading.conditions();

        report.divider();
        report.row(ClimateReport.value(BuiltInRegistries.BLOCK.getKey(block)));

        MutableComponent tempWant = ClimateReport.value(TemperatureUnits.formatRange(band.tempLo(), band.tempHi(), units));
        if (c.waterTemp()) {
            tempWant.append(ClimateReport.key("water").withStyle(ChatFormatting.DARK_GRAY));
        }
        report.row("wants_temp", tempWant, ClimateReport.mark(c.tempF(), band.tempLo(), band.tempHi()));

        MutableComponent humidityMark = c.waiver() == GrowthGovernor.HumidityWaiver.SUBMERGED
                ? ClimateReport.key("mark.ok").withStyle(ChatFormatting.GREEN)
                : ClimateReport.mark(c.humidity(), band.moistLo(), band.moistHi());
        report.row("wants_humidity",
                ClimateReport.value(Math.round(band.moistLo() * 100) + "–" + Math.round(band.moistHi() * 100) + "%"),
                humidityMark);

        if (c.waiver() == GrowthGovernor.HumidityWaiver.ENCLOSED) {
            report.row(ClimateReport.key("enclosed", ClimateReport.pct(c.humidity())).withStyle(ChatFormatting.GREEN));
        } else if (c.waiver() == GrowthGovernor.HumidityWaiver.SUBMERGED) {
            report.row(ClimateReport.key("submerged").withStyle(ChatFormatting.GREEN));
        }

        report.divider();
        growthRow(report, reading.total());
        return report;
    }

    private static void growthRow(ClimateReport report, double total) {
        double growthMax = CropClimatesConfig.GROWTH_MAX.get();
        Verdict verdict = Verdict.of(total, growthMax);
        report.row("growth",
                Component.literal(String.format(Locale.ROOT, "%.2f", total)).withStyle(verdict.color),
                Component.literal(bar(total, growthMax)).withStyle(ChatFormatting.DARK_GRAY),
                verdict.label());
        if (total < 0.85) {
            report.row(ClimateReport.key("advice").withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    /**
     * A hygrometer's report: greenhouse status, the humidity it reads, and for
     * a greenhouse what is inside it.
     */
    public static ClimateReport hygrometer(Level level, BlockPos pos, GreenhouseStatus status, @Nullable Room room,
                                           CropClimatesConfig.Units units) {
        ClimateReport report = new ClimateReport(ClimateReport.key("hygrometer"));

        switch (status) {
            case GREENHOUSE -> report.row(ClimateReport.key("hygrometer.greenhouse").withStyle(ChatFormatting.GREEN));
            case OUTDOOR -> report.row(ClimateReport.key("hygrometer.outdoor").withStyle(ChatFormatting.YELLOW));
            case TOO_LARGE -> report.row(ClimateReport.key("hygrometer.too_large",
                    ClimateReport.value(CropClimatesConfig.GREENHOUSE_MAX_VOLUME.get())).withStyle(ChatFormatting.YELLOW));
            case SCANNING -> report.row(ClimateReport.key("hygrometer.scanning").withStyle(ChatFormatting.GRAY));
            case DISABLED -> report.row(ClimateReport.key("hygrometer.disabled").withStyle(ChatFormatting.GRAY));
        }

        if (status == GreenhouseStatus.GREENHOUSE && room != null) {
            BlockPos anchor = room.anchorPos();
            String biomeId = level.getBiome(anchor).unwrapKey().map(k -> k.location().toString()).orElse("unknown");
            report.row("hygrometer.humidity", ClimateReport.pct(room.humidity(level)));
            report.row("hygrometer.base", ClimateReport.value(biomeId), ClimateReport.pct(room.baseHumidity(level)));
            report.divider();
            report.row("hygrometer.size", ClimateReport.value(room.size()),
                    ClimateReport.value(CropClimatesConfig.GREENHOUSE_MAX_VOLUME.get()));
            report.row("hygrometer.sources",
                    ClimateReport.value(room.sourceCount(EnclosureHumidity.Effect.WATER)),
                    ClimateReport.value(room.sourceCount(EnclosureHumidity.Effect.HUMIDIFIER)),
                    ClimateReport.value(room.sourceCount(EnclosureHumidity.Effect.DESICCANT)),
                    ClimateReport.value(room.sourceCount(EnclosureHumidity.Effect.LAVA)));
        } else {
            HumiditySource.Outdoor outdoor = HumiditySource.outdoor(level, pos);
            String biomeId = level.getBiome(pos).unwrapKey().map(k -> k.location().toString()).orElse("unknown");
            report.row("hygrometer.humidity", ClimateReport.pct(outdoor.humidity()));
            report.row("hygrometer.base", ClimateReport.value(biomeId), ClimateReport.pct(outdoor.biome()));
            if (outdoor.raining()) {
                report.row(ClimateReport.key("hygrometer.rain").withStyle(ChatFormatting.AQUA));
            }
        }

        OptionalDouble tempF = ClimateSampler.temperatureF(level, pos);
        if (tempF.isPresent()) {
            report.divider();
            report.row("hygrometer.temp", ClimateReport.value(TemperatureUnits.format(tempF.getAsDouble(), units)));
        }
        return report;
    }

    private static String bar(double total, double growthMax) {
        int filled = (int) Math.round((Math.min(total, growthMax) / growthMax) * 10);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 10; i++) {
            sb.append(i < filled ? '|' : '.');
        }
        return sb.append(']').toString();
    }
}
