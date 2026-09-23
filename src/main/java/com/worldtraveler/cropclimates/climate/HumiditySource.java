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
        boolean raining = rainReaches(level, pos);
        double humidity = raining ? clamp(biome + CropClimatesConfig.RAIN_HUMIDITY_SHIFT.get()) : biome;
        return new Outdoor(biome, humidity, raining);
    }

    /**
     * Whether rain is falling on the plant at {@code pos}. A plant that blocks
     * motion - bamboo, a cactus - tops the MOTION_BLOCKING heightmap itself,
     * so {@link Level#isRainingAt} never counts it as rained on; the rain
     * lands on the cell above it, so that is the one checked.
     */
    static boolean rainReaches(Level level, BlockPos pos) {
        if (!level.isRaining()) {
            return false;
        }
        return level.isRainingAt(pos) || (level.getBlockState(pos).blocksMotion() && level.isRainingAt(pos.above()));
    }

    static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
