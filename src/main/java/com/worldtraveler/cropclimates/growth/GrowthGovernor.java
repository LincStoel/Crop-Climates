package com.worldtraveler.cropclimates.growth;

import com.worldtraveler.cropclimates.CropClimatesConfig;
import com.worldtraveler.cropclimates.climate.BiomeMoisture;
import com.worldtraveler.cropclimates.climate.ClimateBand;
import com.worldtraveler.cropclimates.climate.ClimateBands;
import com.worldtraveler.cropclimates.climate.ClimateSampler;
import com.worldtraveler.cropclimates.climate.EnclosureSampler;
import com.worldtraveler.cropclimates.climate.GrowthModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.biome.Biome;

import java.util.OptionalDouble;

/**
 * {@code wtMultiplier}: band lookup -> temperature (air or water) ->
 * humidity (with the submerged/enclosed waivers) -> curve -> sky penalty.
 * Returns {@code null} to mean "leave this tick completely alone" - never
 * touch a plant scoring on an unresolvable axis.
 */
public final class GrowthGovernor {

    private GrowthGovernor() {
    }

    public record GrowthReading(
            double total, double tempF, double moisture, double effectiveMoisture,
            double fitT, double fitM,
            boolean seesSky, boolean submerged, boolean waterTemp,
            HumidityWaiver waiver) {
        public enum HumidityWaiver { NONE, SUBMERGED, ENCLOSED }
    }

    /** Returns {@code null} if this tick should be left completely alone. */
    public static GrowthReading read(Level level, BlockPos pos, BlockState state, boolean explain) {
        Block block = state.getBlock();
        ClimateBand band = ClimateBands.bandFor(block);
        if (band == null) {
            return null;
        }

        boolean submerged = band.aquatic()
                && CropClimatesConfig.AQUATIC_USES_WATER_TEMPERATURE.get()
                && level.getFluidState(pos).is(FluidTags.WATER);

        OptionalDouble tempF = submerged
                ? ClimateSampler.waterTemperatureF(level, pos)
                : ClimateSampler.temperatureF(level, pos);
        if (tempF.isEmpty()) {
            return null;
        }

        double give = ClimateBands.isSapling(block) ? CropClimatesConfig.TREE_FORGIVENESS.get() : 1.0;

        Holder<Biome> biome = level.getBiome(pos);
        double moisture = BiomeMoisture.moistureOf(biome, CropClimatesConfig.DEFAULT_BIOME_MOISTURE.get());

        double fitM = GrowthModel.fitEdge(moisture, band.moistLo(), band.moistHi(),
                CropClimatesConfig.MOIST_TOLERANCE.get() * give);

        GrowthReading.HumidityWaiver waiver = GrowthReading.HumidityWaiver.NONE;
        double effectiveMoisture = moisture;
        if (fitM < 1.0 || explain) {
            if (band.aquatic() && submerged) {
                waiver = GrowthReading.HumidityWaiver.SUBMERGED;
                fitM = 1.0;
            } else if (CropClimatesConfig.GREENHOUSE_ENABLED.get()) {
                // explain is only ever true for the Soil Tester - force a fresh
                // fill so a manual check reflects the room's current contents.
                EnclosureSampler.Reading enclosure = EnclosureSampler.sample(level, pos, moisture, explain);
                if (enclosure.enclosed()) {
                    waiver = GrowthReading.HumidityWaiver.ENCLOSED;
                    effectiveMoisture = enclosure.humidity();
                    fitM = GrowthModel.fitEdge(effectiveMoisture, band.moistLo(), band.moistHi(),
                            CropClimatesConfig.MOIST_TOLERANCE.get() * give);
                }
            }
        }

        double fitT = GrowthModel.fit(tempF.getAsDouble(), band.tempLo(), band.tempHi(),
                CropClimatesConfig.TEMP_TOLERANCE.get() * give);

        double total = GrowthModel.total(fitT, fitM,
                CropClimatesConfig.GROWTH_FLOOR.get(), CropClimatesConfig.GROWTH_MAX.get(), CropClimatesConfig.GROWTH_CURVE.get());

        boolean seesSky = level.canSeeSky(pos);
        if (!seesSky) {
            total *= CropClimatesConfig.SKY_PENALTY.get();
        }

        return new GrowthReading(total, tempF.getAsDouble(), moisture, effectiveMoisture, fitT, fitM,
                seesSky, submerged, submerged, waiver);
    }
}
