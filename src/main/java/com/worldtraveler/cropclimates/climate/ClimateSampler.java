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
 *
 * <p>Over budget, a stale reading is used; only a cell with no reading at all
 * goes unscored for that tick. That makes keeping readings around what keeps
 * big farms governed, so the cache only evicts at its cap, and then only the
 * cells nobody has asked about lately.
 */
public final class ClimateSampler {

    private static final Logger LOGGER = LogUtils.getLogger();

    private record CacheKey(ResourceKey<Level> dimension, long packedPos) {
    }

    /** One cached reading; {@code lastUsed} is refreshed on every hit so eviction drops idle cells first. */
    private static final class CacheEntry {
        final long tick;
        final double fahrenheit;
        volatile long lastUsed;

        CacheEntry(long tick, double fahrenheit) {
            this.tick = tick;
            this.fahrenheit = fahrenheit;
            this.lastUsed = tick;
        }
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
        if (hit != null && now < hit.tick) {
            hit = null;
        }
        if (hit != null) {
            hit.lastUsed = now;
            if (now - hit.tick < CropClimatesConfig.TEMP_CACHE_TTL.get()) {
                return OptionalDouble.of(hit.fahrenheit);
            }
        }

        if (!withinBudget(now)) {
            // Over budget this tick - a stale reading beats a stampede of area
            // scans when a big farm loads; with nothing cached, decline to score.
            return hit != null ? OptionalDouble.of(hit.fahrenheit) : OptionalDouble.empty();
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
            evict(now);
        }
        CACHE.put(key, new CacheEntry(now, fahrenheit));
        return OptionalDouble.of(fahrenheit);
    }

    /**
     * Makes room once the cache reaches its cap: first cells nobody has asked
     * about for a long while, then, if that frees too little, the least
     * recently used half. This used to empty the whole cache, which left every
     * cell of a big farm without a reading at once - past 4000 cells, 40-70%
     * of crop ticks on the stress server went unscored (vanilla speed).
     */
    private static void evict(long now) {
        int cap = CropClimatesConfig.TEMP_CACHE_CAP.get();
        long idle = 20L * CropClimatesConfig.TEMP_CACHE_TTL.get();
        CACHE.values().removeIf(e -> now < e.tick || now - e.lastUsed > idle);
        if (CACHE.size() >= cap * 3L / 4) {
            long[] used = CACHE.values().stream().mapToLong(e -> e.lastUsed).sorted().toArray();
            if (used.length > 0) {
                long cutoff = used[used.length / 2];
                CACHE.values().removeIf(e -> e.lastUsed <= cutoff);
            }
        }
    }

    /** Drops every cached reading and resets the budget - called when a server stops. */
    public static void clear() {
        CACHE.clear();
        budgetTick = Long.MIN_VALUE;
        budgetSpent.set(0);
        coldSweatAvailable = true; // a failed read disables scoring until the server stops
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
