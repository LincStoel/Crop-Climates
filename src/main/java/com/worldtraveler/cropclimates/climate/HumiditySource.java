package com.worldtraveler.cropclimates.climate;

import com.worldtraveler.cropclimates.CropClimatesConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Outdoor humidity: the biome's value, raised by {@code rainHumidityShift}
 * while rain is actually falling on the spot. {@link Level#isRainingAt} checks
 * the motion-blocking heightmap and the biome's precipitation, so any roof -
 * glass included - snow and rainless biomes all correctly get nothing.
 * Greenhouse humidity is handled by the greenhouse itself and never sees rain.
 */
public final class HumiditySource {

    public record Outdoor(double biome, double humidity, boolean raining) {
    }

    private HumiditySource() {
    }

    public static Outdoor outdoor(Level level, BlockPos pos) {
        double biome = BiomeMoisture.moistureOf(level.getBiome(pos), CropClimatesConfig.DEFAULT_BIOME_MOISTURE.get());
        boolean raining = level.isRainingAt(pos);
        double humidity = raining ? clamp(biome + CropClimatesConfig.RAIN_HUMIDITY_SHIFT.get()) : biome;
        return new Outdoor(biome, humidity, raining);
    }

    static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
