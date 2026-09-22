package com.worldtraveler.cropclimates.growth;

import com.worldtraveler.cropclimates.CropClimatesConfig;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.level.block.CropGrowEvent;
import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code CropGrowEvent.Pre}/{@code .Post} - the hook for every plant that
 * funnels through {@code CommonHooks.canCropGrow}. Slows growth by vetoing
 * ticks and speeds it up by driving one extra random tick, guarded by a
 * re-entrancy flag and a graduated error budget.
 *
 * <p>A random-tick hook must never be able to crash a world: every entry
 * point here is wrapped, failures are logged up to
 * {@link CropClimatesConfig#ERROR_LIMIT}, and past that the whole system
 * disables itself and reverts to vanilla growth for the session.
 */
public final class CropGrowHandlers {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile boolean disabled = false;
    private static volatile boolean forcing = false;
    private static volatile boolean speedupOk = true;
    private static final AtomicInteger errors = new AtomicInteger();

    private CropGrowHandlers() {
    }

    public static boolean isDisabled() {
        return disabled;
    }

    public static boolean isForcing() {
        return forcing;
    }

    public static int errorCount() {
        return errors.get();
    }

    public static boolean isSpeedupAvailable() {
        return speedupOk;
    }

    public static void onPre(CropGrowEvent.Pre event) {
        if (disabled) {
            return;
        }
        try {
            if (forcing) {
                // We are driving the extra tick ourselves - it must succeed.
                event.setResult(CropGrowEvent.Pre.Result.GROW);
                return;
            }
            ServerLevel level = (ServerLevel) event.getLevel();
            BlockPos pos = event.getPos();
            GrowthGovernor.GrowthReading reading = GrowthGovernor.read(level, pos, event.getState());
            if (reading == null || reading.total() >= 1.0) {
                return;
            }
            if (level.getRandom().nextDouble() >= reading.total()) {
                event.setResult(CropGrowEvent.Pre.Result.DO_NOT_GROW);
                Regression.consider(level, pos, event.getState(), reading);
            }
        } catch (RuntimeException ex) {
            fail("CropGrowEvent.Pre", ex);
        }
    }

    public static void onPost(CropGrowEvent.Post event) {
        if (disabled || forcing) {
            return;
        }
        try {
            ServerLevel level = (ServerLevel) event.getLevel();
            BlockPos pos = event.getPos();
            GrowthGovernor.GrowthReading reading = GrowthGovernor.read(level, pos, event.getState());
            if (reading == null || reading.total() <= 1.0) {
                return;
            }
            // Post only fires after a growth that already succeeded, so one
            // extra tick at probability (total - 1) lands on exactly `total`
            // times the vanilla rate.
            if (level.getRandom().nextDouble() < reading.total() - 1.0) {
                extraTick(level, pos);
            }
        } catch (RuntimeException ex) {
            fail("CropGrowEvent.Post", ex);
        }
    }

    /** Also called by the randomTick mixin for the sapling/own-tick half. */
    public static void extraTick(ServerLevel level, BlockPos pos) {
        if (forcing || !speedupOk) {
            return;
        }
        forcing = true;
        try {
            BlockState state = level.getBlockState(pos);
            state.randomTick(level, pos, level.getRandom());
        } catch (RuntimeException ex) {
            speedupOk = false;
            LOGGER.warn("crop_climates: extra growth tick unavailable, plants can be slowed but not sped up this session", ex);
        } finally {
            forcing = false;
        }
    }

    public static void fail(String where, RuntimeException ex) {
        int count = errors.incrementAndGet();
        int limit = CropClimatesConfig.ERROR_LIMIT.get();
        if (count <= limit) {
            LOGGER.error("crop_climates: error in {}", where, ex);
        }
        if (count >= limit) {
            disabled = true;
            LOGGER.error("crop_climates: too many errors, DISABLING climate growth for this session. "
                    + "Plants revert to vanilla speed. Fix the error above and restart the server.");
        }
    }
}
