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
 * live, cached per coarse 4x8x4 cell for {@code tempCacheTtl} ticks and
 * metered by a per-tick read budget. {@code Temperature.convert(mc, MC, F, true)}
 * turns Cold Sweat's internal units into the Fahrenheit the bands use.
 */
public final class ClimateSampler {

    private static final Logger LOGGER = LogUtils.getLogger();

    private record CacheKey(ResourceKey<Level> dimension, long packedPos) {
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

        long now = level.getGameTime();
        CacheKey key = new CacheKey(level.dimension(), packedCell(pos));
        CacheEntry hit = CACHE.get(key);
        // now < tick means the entry came from another world (singleplayer
        // world switch keeps statics alive) - never trust it.
        if (hit != null && now >= hit.tick() && now - hit.tick() < CropClimatesConfig.TEMP_CACHE_TTL.get()) {
            return OptionalDouble.of(hit.fahrenheit());
        }

        if (!withinBudget(now)) {
            // Over budget this tick - a stale reading beats a stampede of area
            // scans when a big farm loads; with nothing cached, decline to score.
            return hit != null && now >= hit.tick() ? OptionalDouble.of(hit.fahrenheit()) : OptionalDouble.empty();
        }

        double mc;
        try {
            mc = WorldHelper.getRoughTemperatureAt(level, pos);
        } catch (RuntimeException ex) {
            coldSweatAvailable = false;
            LOGGER.warn("crop_climates: getRoughTemperatureAt failed, temperature scoring disabled for this session", ex);
            return OptionalDouble.empty();
        }
        double fahrenheit = Temperature.convert(mc, Temperature.Units.MC, Temperature.Units.F, true);

        if (CACHE.size() >= CropClimatesConfig.TEMP_CACHE_CAP.get()) {
            CACHE.clear();
        }
        CACHE.put(key, new CacheEntry(now, fahrenheit));
        return OptionalDouble.of(fahrenheit);
    }

    /** Drops every cached reading and resets the budget - called when a server stops. */
    public static void clear() {
        CACHE.clear();
        budgetTick = Long.MIN_VALUE;
        budgetSpent.set(0);
    }

    public static int cacheSize() {
        return CACHE.size();
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
