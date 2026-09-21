package com.worldtraveler.cropclimates.climate;

import com.momosoftworks.coldsweat.api.util.Temperature;
import com.momosoftworks.coldsweat.util.world.WorldHelper;
import com.mojang.logging.LogUtils;
import com.worldtraveler.cropclimates.CropClimatesConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Temperature axis. Reads Cold Sweat's {@code WorldHelper.getRoughTemperatureAt}
 * live, per block, with the same cache semantics as the KubeJS
 * {@code wtLocalTempF} - see {@code design_reference_kubejs_implementation.md}
 * section 6. {@code Temperature.convert(mc, MC, F, true)} replaces the
 * hand-rolled {@code mc * 45 + 32}; the two are bytecode-identical.
 */
public final class ClimateSampler {

    private static final Logger LOGGER = LogUtils.getLogger();

    private record CacheKey(ResourceKey<Level> dimension, long packedPos, boolean water) {
    }

    private record CacheEntry(long tick, double fahrenheit) {
    }

    private static final ConcurrentHashMap<CacheKey, CacheEntry> CACHE = new ConcurrentHashMap<>();

    private static volatile long budgetTick = Long.MIN_VALUE;
    private static final AtomicInteger budgetSpent = new AtomicInteger();

    private static volatile boolean coldSweatAvailable = true;

    private ClimateSampler() {
    }

    public static boolean isColdSweatAvailable() {
        return coldSweatAvailable;
    }

    /** Local temperature in Fahrenheit, or empty if it cannot be resolved right now. */
    public static OptionalDouble temperatureF(Level level, BlockPos pos) {
        if (!coldSweatAvailable) {
            return OptionalDouble.empty();
        }

        return read(level, pos, false);
    }

    /**
     * Water temperature in Fahrenheit for a submerged aquatic crop, or empty
     * if it cannot be resolved right now. Shares the cache, read budget and
     * kill switch with {@link #temperatureF}, distinguished by a bit in the
     * key.
     */
    public static OptionalDouble waterTemperatureF(Level level, BlockPos pos) {
        return read(level, pos, true);
    }

    private static OptionalDouble read(Level level, BlockPos pos, boolean water) {
        if (!coldSweatAvailable) {
            return OptionalDouble.empty();
        }

        long now = level.getGameTime();
        CacheKey key = new CacheKey(level.dimension(), packedCell(pos), water);
        CacheEntry hit = CACHE.get(key);
        if (hit != null && now - hit.tick() < CropClimatesConfig.TEMP_CACHE_TTL.get()) {
            return OptionalDouble.of(hit.fahrenheit());
        }

        if (!withinBudget(now)) {
            // Over budget this tick - a stale reading beats a stampede of area
            // scans when a big farm loads; with nothing cached, decline to score.
            return hit != null ? OptionalDouble.of(hit.fahrenheit()) : OptionalDouble.empty();
        }

        double mc;
        try {
            mc = water ? WorldHelper.getWaterTemperatureAt(level, pos) : WorldHelper.getRoughTemperatureAt(level, pos);
        } catch (RuntimeException ex) {
            coldSweatAvailable = false;
            LOGGER.warn("crop_climates: {} failed, temperature scoring disabled for this session",
                    water ? "getWaterTemperatureAt" : "getRoughTemperatureAt", ex);
            return OptionalDouble.empty();
        }
        double fahrenheit = Temperature.convert(mc, Temperature.Units.MC, Temperature.Units.F, true);

        if (CACHE.size() >= CropClimatesConfig.TEMP_CACHE_CAP.get()) {
            CACHE.clear();
        }
        CACHE.put(key, new CacheEntry(now, fahrenheit));
        return OptionalDouble.of(fahrenheit);
    }

    private static boolean withinBudget(long now) {
        synchronized (ClimateSampler.class) {
            if (now != budgetTick) {
                budgetTick = now;
                budgetSpent.set(0);
            }
        }
        return budgetSpent.incrementAndGet() <= CropClimatesConfig.TEMP_READS_PER_TICK.get();
    }

    private static long packedCell(BlockPos pos) {
        return BlockPos.asLong(pos.getX() >> 2, pos.getY() >> 3, pos.getZ() >> 2);
    }
}
