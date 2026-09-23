package com.worldtraveler.cropclimates.growth;

import com.worldtraveler.cropclimates.CropClimatesConfig;
import com.worldtraveler.cropclimates.climate.ClimateBand;
import com.worldtraveler.cropclimates.climate.ClimateBands;
import com.worldtraveler.cropclimates.climate.ClimateSampler;
import com.worldtraveler.cropclimates.climate.GrowthModel;
import com.worldtraveler.cropclimates.climate.HumiditySource;
import com.worldtraveler.cropclimates.greenhouse.Greenhouses;
import com.worldtraveler.cropclimates.greenhouse.Room;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.GrowingPlantBodyBlock;
import net.minecraft.world.level.block.GrowingPlantHeadBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.OptionalDouble;

/**
 * Scores a spot for a band in two steps, so every consumer - crop ticks, the
 * Soil Tester, {@code /cropclimates explain}, Jade and tooltip verdicts -
 * runs exactly the same math:
 * <ol>
 *   <li>{@link #resolve} reads the world: temperature, humidity, sky.</li>
 *   <li>{@link #score} is pure: fits, curve, sky penalty.</li>
 * </ol>
 * Humidity comes from, in order: being submerged (aquatic crops - waived),
 * the greenhouse the spot is in (the room's humidity, in both directions),
 * else the biome plus rain actually falling there.
 */
public final class GrowthGovernor {

    /** How far along a stalk {@link #growingEnd} will look for the growing end. */
    private static final int MAX_STALK = 64;

    private GrowthGovernor() {
    }

    public enum HumidityWaiver { NONE, SUBMERGED, ENCLOSED }

    /**
     * Everything read from the world for one spot.
     *
     * @param biomeMoisture the biome's own humidity at the spot
     * @param humidity      what the band is scored against (greenhouse, rain applied)
     * @param room          the greenhouse, when {@code waiver == ENCLOSED}
     */
    public record Conditions(double tempF, double biomeMoisture, double humidity,
                             boolean raining, HumidityWaiver waiver, @Nullable Room room, boolean seesSky) {
    }

    public record GrowthReading(double total, double fitT, double fitM, Conditions conditions) {
        public double tempF() {
            return conditions.tempF();
        }

        public HumidityWaiver waiver() {
            return conditions.waiver();
        }
    }

    /** Reads the world for {@code band} at {@code pos}; {@code null} if temperature is unavailable. */
    @Nullable
    public static Conditions resolve(Level level, BlockPos pos, ClimateBand band) {
        boolean submerged = band.aquatic() && level.getFluidState(pos).is(FluidTags.WATER);

        OptionalDouble tempF = ClimateSampler.temperatureF(level, pos);
        if (tempF.isEmpty()) {
            return null;
        }

        HumiditySource.Outdoor outdoor = HumiditySource.outdoor(level, pos);
        Room room = submerged ? null : Greenhouses.roomAt(level, pos);
        HumidityWaiver waiver;
        double humidity;
        boolean raining;
        if (submerged) {
            waiver = HumidityWaiver.SUBMERGED;
            humidity = outdoor.biome();
            raining = false;
        } else if (room != null) {
            waiver = HumidityWaiver.ENCLOSED;
            humidity = room.humidity(level);
            raining = false;
        } else {
            waiver = HumidityWaiver.NONE;
            humidity = outdoor.humidity();
            raining = outdoor.raining();
        }

        // Water dims skylight a level per block, so a submerged crop would
        // never "see the sky" - sunlight is not held against it.
        return new Conditions(tempF.getAsDouble(), outdoor.biome(), humidity, raining, waiver, room,
                submerged || level.canSeeSky(pos));
    }

    /** Pure scoring of already-resolved conditions. */
    public static GrowthReading score(ClimateBand band, Conditions c, boolean sapling) {
        double give = sapling ? CropClimatesConfig.TREE_FORGIVENESS.get() : 1.0;
        double fitT = GrowthModel.fit(c.tempF(), band.tempLo(), band.tempHi(),
                CropClimatesConfig.TEMP_TOLERANCE.get() * give);
        double fitM = c.waiver() == HumidityWaiver.SUBMERGED
                ? 1.0
                : GrowthModel.fitEdge(c.humidity(), band.moistLo(), band.moistHi(),
                        CropClimatesConfig.MOIST_TOLERANCE.get() * give);

        double total = GrowthModel.total(fitT, fitM,
                CropClimatesConfig.GROWTH_FLOOR.get(), CropClimatesConfig.GROWTH_MAX.get(), CropClimatesConfig.GROWTH_CURVE.get());
        if (!c.seesSky()) {
            total *= CropClimatesConfig.SKY_PENALTY.get();
        }
        return new GrowthReading(total, fitT, fitM, c);
    }

    /**
     * The reading for the governed plant at {@code pos}, or {@code null} to
     * leave this tick alone (no band, or temperature unavailable).
     */
    @Nullable
    public static GrowthReading read(Level level, BlockPos pos, BlockState state) {
        ClimateBand band = ClimateBands.bandFor(state.getBlock());
        if (band == null) {
            return null;
        }
        Conditions conditions = resolve(level, pos, band);
        if (conditions == null) {
            return null;
        }
        return score(band, conditions, ClimateBands.isSapling(state.getBlock()));
    }

    /**
     * The cell a standing entity occupies for climate readings: the block at
     * its feet, or the one above when it stands on a partial block such as
     * farmland, a path or a slab. There {@code blockPosition()} is the floor
     * block itself, which no rain falls on.
     */
    public static BlockPos standingCell(Entity entity) {
        BlockPos pos = entity.blockPosition();
        Level level = entity.level();
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty() ? pos : pos.above();
    }

    /**
     * The block that actually grows for whatever part of a plant was clicked
     * or looked at: the head of a kelp or vine stalk, or the top of a bamboo,
     * sugar cane or cactus column. Anything else is returned as is. The Soil
     * Tester and Jade both read here, since temperature can change along a
     * tall stalk and only this block's reading drives growth.
     */
    public static BlockPos growingEnd(Level level, BlockPos pos) {
        Block block = level.getBlockState(pos).getBlock();
        if (block instanceof GrowingPlantBodyBlock) {
            for (Direction dir : new Direction[]{Direction.UP, Direction.DOWN}) {
                BlockPos.MutableBlockPos cursor = pos.mutable();
                for (int i = 0; i < MAX_STALK && level.getBlockState(cursor).is(block); i++) {
                    cursor.move(dir);
                }
                if (level.getBlockState(cursor).getBlock() instanceof GrowingPlantHeadBlock) {
                    return cursor.immutable();
                }
            }
            return pos;
        }
        if (ClimateBands.bandFor(block) != null && level.getBlockState(pos.above()).is(block)) {
            BlockPos.MutableBlockPos cursor = pos.mutable();
            for (int i = 0; i < MAX_STALK && level.getBlockState(cursor.above()).is(block); i++) {
                cursor.move(Direction.UP);
            }
            return cursor.immutable();
        }
        return pos;
    }
}
