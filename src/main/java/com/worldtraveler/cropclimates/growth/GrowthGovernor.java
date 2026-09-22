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
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.OptionalDouble;

/**
 * Scores a spot for a band in two steps, so every consumer - crop ticks, the
 * Soil Tester, {@code /cropclimates explain}, Jade and tooltip verdicts -
 * runs exactly the same math:
 * <ol>
 *   <li>{@link #resolve} reads the world: temperature (air, or water for a
 *       submerged aquatic crop), humidity, sky.</li>
 *   <li>{@link #score} is pure: fits, curve, sky penalty.</li>
 * </ol>
 * Humidity comes from, in order: being submerged (aquatic crops - waived),
 * the greenhouse the spot is in (the room's humidity, in both directions),
 * else the biome plus rain actually falling there.
 */
public final class GrowthGovernor {

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
    public record Conditions(double tempF, boolean waterTemp, double biomeMoisture, double humidity,
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
        boolean submerged = band.aquatic()
                && CropClimatesConfig.AQUATIC_USES_WATER_TEMPERATURE.get()
                && level.getFluidState(pos).is(FluidTags.WATER);

        OptionalDouble tempF = submerged
                ? ClimateSampler.waterTemperatureF(level, pos)
                : ClimateSampler.temperatureF(level, pos);
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

        return new Conditions(tempF.getAsDouble(), submerged, outdoor.biome(), humidity, raining, waiver, room,
                level.canSeeSky(pos));
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
}
