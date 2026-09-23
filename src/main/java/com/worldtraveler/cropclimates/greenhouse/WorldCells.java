package com.worldtraveler.cropclimates.greenhouse;

import com.momosoftworks.coldsweat.api.registry.SpreadRuleRegistry;
import com.momosoftworks.coldsweat.api.spread_rule.DefaultSpreadRule;
import com.momosoftworks.coldsweat.api.spread_rule.SpreadContext;
import com.momosoftworks.coldsweat.api.spread_rule.SpreadRule;
import com.momosoftworks.coldsweat.config.ConfigSettings;
import com.momosoftworks.coldsweat.util.world.WorldHelper;
import com.mojang.logging.LogUtils;
import com.worldtraveler.cropclimates.CropClimatesConfig;
import com.worldtraveler.cropclimates.CropClimatesTags;
import com.worldtraveler.cropclimates.climate.EnclosureHumidity;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.slf4j.Logger;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * World-backed {@link RoomScan.Steps}/{@link RoomScan.Cells} for one scan.
 * The only place greenhouse code calls Cold Sweat: its hearth spread rules
 * decide where air can go, and its sky test decides what counts as a leak -
 * so a room that holds hearth air is exactly a room that can be a greenhouse.
 * If Cold Sweat ever throws here, greenhouses disable themselves for the
 * session, logged once.
 */
final class WorldCells implements RoomScan.Steps, RoomScan.Cells {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile boolean available = true;

    private final ServerLevel level;
    private final int skyScan;
    /** Column -> (y << 1 | result) of the last sky test in that column this scan. */
    private final Long2LongOpenHashMap skyCache = new Long2LongOpenHashMap();
    /** Default-rule spread verdicts this scan, per source state: 0 unknown, 1 no, 2 yes, by inDir * 6 + outDir. */
    private final Map<BlockState, byte[]> stepMemo = new IdentityHashMap<>();

    WorldCells(ServerLevel level) {
        this.level = level;
        this.skyScan = CropClimatesConfig.GREENHOUSE_SKY_SCAN.get();
        skyCache.defaultReturnValue(Long.MIN_VALUE);
    }

    static boolean isAvailable() {
        return available;
    }

    /**
     * Whether Cold Sweat's config names this block in its spread whitelist
     * (hearth air passes it despite its shape) or blacklist (it stops hearth
     * air despite its shape). Its default config lists leaves and water.
     * Assumes it does if the lists can't be read.
     */
    static boolean isSpreadListed(BlockState state) {
        if (!available) {
            return true;
        }
        try {
            Block block = state.getBlock();
            return ConfigSettings.THERMAL_SOURCE_SPREAD_WHITELIST.get().contains(block)
                    || ConfigSettings.THERMAL_SOURCE_SPREAD_BLACKLIST.get().contains(block);
        } catch (RuntimeException | LinkageError ex) {
            return true;
        }
    }

    static void fail(RuntimeException ex) {
        if (available) {
            available = false;
            LOGGER.warn("crop_climates: greenhouse update failed, greenhouses disabled for this session "
                    + "(crops use outdoor humidity until the server restarts)", ex);
        }
    }

    @Override
    public boolean isLoaded(BlockPos pos) {
        return level.isLoaded(pos);
    }

    @Override
    public boolean isOpen(BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.isAir() || !state.getFluidState().isEmpty();
    }

    @Override
    public boolean seesSky(BlockPos pos) {
        long column = BlockPos.asLong(pos.getX(), 0, pos.getZ());
        long cached = skyCache.get(column);
        if (cached != Long.MIN_VALUE) {
            int y = (int) (cached >> 1);
            boolean result = (cached & 1L) != 0;
            // Seeing sky from y means seeing it from anything above; being
            // blocked at y means being blocked below it too.
            if (result && pos.getY() >= y) {
                return true;
            }
            if (!result && pos.getY() <= y) {
                return false;
            }
        }
        boolean result = WorldHelper.canSeeSky(level, pos, skyScan);
        skyCache.put(column, ((long) pos.getY() << 1) | (result ? 1L : 0L));
        return result;
    }

    /**
     * Water/humidifier raise humidity, lava/desiccant lower it. A cell only
     * matches one case; a waterlogged desiccant or humidifier scores as water
     * since the fluid check runs first.
     */
    @Override
    public EnclosureHumidity.Effect effect(BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        FluidState fluid = state.getFluidState();
        if (fluid.is(FluidTags.WATER)) {
            return EnclosureHumidity.Effect.WATER;
        }
        if (fluid.is(FluidTags.LAVA)) {
            return EnclosureHumidity.Effect.LAVA;
        }
        if (state.is(CropClimatesTags.DESICCANT)) {
            return EnclosureHumidity.Effect.DESICCANT;
        }
        if (state.is(CropClimatesTags.HUMIDIFIER)) {
            return EnclosureHumidity.Effect.HUMIDIFIER;
        }
        return EnclosureHumidity.Effect.NONE;
    }

    @Override
    public boolean canStep(BlockPos from, Direction inDir, BlockPos to, Direction outDir) {
        BlockState fromState = level.getBlockState(from);
        SpreadRule rule = SpreadRuleRegistry.get(fromState);
        // The default rule's verdict depends on the source's collision shape,
        // the two directions and Cold Sweat's config lists - so for a block
        // whose shape never varies it is the same everywhere in a scan, which
        // otherwise asks it up to five times per cell.
        byte[] memo = null;
        int slot = inDir.ordinal() * 6 + outDir.ordinal();
        if (rule.getClass() == DefaultSpreadRule.class && !fromState.getBlock().hasDynamicShape()) {
            memo = stepMemo.computeIfAbsent(fromState, k -> new byte[36]);
            if (memo[slot] != 0) {
                return memo[slot] == 2;
            }
        }
        BlockState toState = level.getBlockState(to);
        boolean result = rule.canSpreadTo(new SpreadContext(level, from, fromState, to, toState, inDir, outDir));
        if (memo != null) {
            memo[slot] = (byte) (result ? 2 : 1);
        }
        return result;
    }
}
