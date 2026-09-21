package com.worldtraveler.cropclimates.climate;

import com.momosoftworks.coldsweat.api.registry.SpreadRuleRegistry;
import com.momosoftworks.coldsweat.api.spread_rule.SpreadContext;
import com.momosoftworks.coldsweat.api.spread_rule.SpreadRule;
import com.momosoftworks.coldsweat.util.world.WorldHelper;
import com.mojang.logging.LogUtils;
import com.worldtraveler.cropclimates.CropClimatesConfig;
import com.worldtraveler.cropclimates.CropClimatesTags;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * World-backed, cached, budgeted greenhouse test. Mirrors
 * {@link ClimateSampler}'s structure one-for-one - the same cache key/entry
 * shape, the same cap-and-clear-wholesale policy, the same per-tick budget,
 * and the same log-once-then-disable kill switch if Cold Sweat's spread
 * rules ever throw.
 *
 * <p>Two deliberate differences from {@code ClimateSampler}: keys are exact
 * positions, not coarse cells (a coarse cell straddling a greenhouse wall
 * would answer for both sides of it), and a positive fill writes an entry
 * for every visited cell - walls included, since room size for the humidity
 * density calc counts them too - so one fill answers for every crop sharing
 * that sealed room.
 */
public final class EnclosureSampler {

    private static final Logger LOGGER = LogUtils.getLogger();

    private record CacheKey(ResourceKey<Level> dimension, long packedPos) {
    }

    private record CacheEntry(long tick, boolean enclosed, double density) {
    }

    /**
     * {@code humidity} is only meaningful when {@code enclosed} is true.
     */
    public record Reading(boolean enclosed, double humidity) {
        public static final Reading NOT_ENCLOSED = new Reading(false, Double.NaN);
    }

    private static final ConcurrentHashMap<CacheKey, CacheEntry> CACHE = new ConcurrentHashMap<>();

    private static volatile long budgetTick = Long.MIN_VALUE;
    private static final AtomicInteger budgetSpent = new AtomicInteger();

    private static volatile boolean coldSweatAvailable = true;

    private EnclosureSampler() {
    }

    public static boolean isColdSweatAvailable() {
        return coldSweatAvailable;
    }

    public static Reading sample(Level level, BlockPos cropPos, double biomeHumidity) {
        return sample(level, cropPos, biomeHumidity, false);
    }

    /**
     * {@code forceRefresh} skips a fresh (non-stale) cache hit and re-runs the
     * fill, so every crop in the room picks up the recalculated values too -
     * used by the Soil Tester so a manual check always reflects the room's
     * current contents rather than a stale cached reading.
     */
    public static Reading sample(Level level, BlockPos cropPos, double biomeHumidity, boolean forceRefresh) {
        if (!coldSweatAvailable || !CropClimatesConfig.GREENHOUSE_ENABLED.get()) {
            return Reading.NOT_ENCLOSED;
        }

        long now = level.getGameTime();
        BlockPos origin = cropPos.above();
        CacheKey key = new CacheKey(level.dimension(), origin.asLong());
        CacheEntry hit = CACHE.get(key);
        if (!forceRefresh && hit != null && now - hit.tick() < CropClimatesConfig.GREENHOUSE_CACHE_TTL.get()) {
            return hit.enclosed()
                    ? new Reading(true, clampedHumidity(biomeHumidity, hit.density()))
                    : Reading.NOT_ENCLOSED;
        }

        // A sky escape from the very first cell is one canSeeSky call - too
        // cheap to meter, so it never touches the fill budget.
        BlockState originState = level.getBlockState(origin);
        boolean originOpen = originState.isAir() || !originState.getFluidState().isEmpty();
        if (originOpen && WorldHelper.canSeeSky(level, origin, CropClimatesConfig.GREENHOUSE_SKY_SCAN.get())) {
            CACHE.put(key, new CacheEntry(now, false, 0.0));
            return Reading.NOT_ENCLOSED;
        }

        if (!withinBudget(now)) {
            return hit != null && hit.enclosed()
                    ? new Reading(true, clampedHumidity(biomeHumidity, hit.density()))
                    : Reading.NOT_ENCLOSED;
        }

        boolean enclosed;
        EnclosureFlood.Result result;
        try {
            result = runFill(level, origin);
            enclosed = result.enclosed();
        } catch (RuntimeException ex) {
            coldSweatAvailable = false;
            LOGGER.warn("crop_climates: Cold Sweat spread rule lookup failed, greenhouse waiver disabled for this session", ex);
            return Reading.NOT_ENCLOSED;
        }

        if (CACHE.size() >= CropClimatesConfig.GREENHOUSE_CACHE_CAP.get()) {
            CACHE.clear();
        }

        if (enclosed) {
            int netWeight = 0;
            for (BlockPos visited : result.visited()) {
                BlockState state = level.getBlockState(visited);
                netWeight += classify(state);
            }
            double density = CropClimatesConfig.HUMIDITY_BLOCK_MULTIPLIER.get() * netWeight / (double) result.visited().size();
            CacheEntry entry = new CacheEntry(now, true, density);
            for (BlockPos visited : result.visited()) {
                CACHE.put(new CacheKey(level.dimension(), visited.asLong()), entry);
            }
            return new Reading(true, clampedHumidity(biomeHumidity, density));
        }

        CACHE.put(key, new CacheEntry(now, false, 0.0));
        return Reading.NOT_ENCLOSED;
    }

    private static double clampedHumidity(double biomeHumidity, double density) {
        return Math.max(0.0, Math.min(1.0, biomeHumidity + density));
    }

    /**
     * Water/humidifier raise humidity, lava/desiccant lower it (lava at 3x
     * magnitude) - see {@link EnclosureHumidity#weightOf}. A cell can only
     * match one case; a waterlogged desiccant/humidifier block scores as
     * water since the fluid check runs first.
     */
    private static int classify(BlockState state) {
        FluidState fluid = state.getFluidState();
        if (fluid.is(FluidTags.WATER)) {
            return EnclosureHumidity.weightOf(EnclosureHumidity.Effect.WATER);
        }
        if (fluid.is(FluidTags.LAVA)) {
            return EnclosureHumidity.weightOf(EnclosureHumidity.Effect.LAVA);
        }
        if (state.is(CropClimatesTags.DESICCANT)) {
            return EnclosureHumidity.weightOf(EnclosureHumidity.Effect.DESICCANT);
        }
        if (state.is(CropClimatesTags.HUMIDIFIER)) {
            return EnclosureHumidity.weightOf(EnclosureHumidity.Effect.HUMIDIFIER);
        }
        return EnclosureHumidity.weightOf(EnclosureHumidity.Effect.NONE);
    }

    private static EnclosureFlood.Result runFill(Level level, BlockPos origin) {
        Map<Long, SkyEntry> skyCache = new HashMap<>();

        EnclosureFlood.Cells cells = new EnclosureFlood.Cells() {
            @Override
            public boolean isOpen(BlockPos pos) {
                BlockState state = level.getBlockState(pos);
                return state.isAir() || !state.getFluidState().isEmpty();
            }

            @Override
            public boolean seesSky(BlockPos pos) {
                long columnKey = BlockPos.asLong(pos.getX(), 0, pos.getZ());
                SkyEntry cached = skyCache.get(columnKey);
                if (cached != null) {
                    if (cached.result && pos.getY() >= cached.y) {
                        return true;
                    }
                    if (!cached.result && pos.getY() <= cached.y) {
                        return false;
                    }
                }
                boolean result = WorldHelper.canSeeSky(level, pos, CropClimatesConfig.GREENHOUSE_SKY_SCAN.get());
                skyCache.put(columnKey, new SkyEntry(pos.getY(), result));
                return result;
            }
        };

        EnclosureFlood.Steps steps = (from, inDir, to, outDir) -> {
            BlockState fromState = level.getBlockState(from);
            BlockState toState = level.getBlockState(to);
            SpreadRule rule = SpreadRuleRegistry.get(fromState);
            return rule.canSpreadTo(new SpreadContext(level, from, fromState, to, toState, inDir, outDir));
        };

        EnclosureFlood.Limits limits = new EnclosureFlood.Limits(
                CropClimatesConfig.GREENHOUSE_MAX_VOLUME.get(),
                CropClimatesConfig.GREENHOUSE_MIN_VOLUME.get(),
                CropClimatesConfig.GREENHOUSE_MAX_RADIUS.get(),
                CropClimatesConfig.GREENHOUSE_MAX_HEIGHT.get());

        return EnclosureFlood.flood(origin, steps, cells, limits);
    }

    private static boolean withinBudget(long now) {
        synchronized (EnclosureSampler.class) {
            if (now != budgetTick) {
                budgetTick = now;
                budgetSpent.set(0);
            }
        }
        return budgetSpent.incrementAndGet() <= CropClimatesConfig.GREENHOUSE_FILLS_PER_TICK.get();
    }

    private record SkyEntry(int y, boolean result) {
    }
}
