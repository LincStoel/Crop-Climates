package com.worldtraveler.cropclimates.growth;

import com.worldtraveler.cropclimates.CropClimates;
import com.worldtraveler.cropclimates.CropClimatesConfig;
import com.worldtraveler.cropclimates.climate.ClimateBand;
import com.worldtraveler.cropclimates.climate.ClimateBands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Plants far outside their bands slowly wilt: on a governed tick whose growth
 * roll failed, a plant whose temperature or humidity fit is below
 * {@code regressionFitThreshold} loses one {@code age} step with probability
 * {@code regressionChance}. Saplings drop from stage 1 to 0, then die to a dead
 * bush. Each wilt puffs a downward-drifting particle tinted by its cause - the
 * opposite of bone meal's rising green sparkle.
 *
 * <p>The block change is queued and applied at the end of the level tick,
 * never inside another block's random tick.
 */
public final class Regression {

    public enum Cause {
        COLD(() -> CropClimates.WILT_COLD.get()),
        HOT(() -> CropClimates.WILT_HOT.get()),
        DRY(() -> CropClimates.WILT_DRY.get()),
        WET(() -> CropClimates.WILT_WET.get());

        private final Supplier<SimpleParticleType> particle;

        Cause(Supplier<SimpleParticleType> particle) {
            this.particle = particle;
        }
    }

    private record Pending(BlockPos pos, BlockState expected, Cause cause, boolean tree) {
    }

    private static final Map<ResourceKey<Level>, List<Pending>> PENDING = new HashMap<>();

    private Regression() {
    }

    /** Called after a governed plant's growth roll failed this tick. */
    public static void consider(ServerLevel level, BlockPos pos, BlockState state, GrowthGovernor.GrowthReading reading) {
        if (!CropClimatesConfig.REGRESSION_ENABLED.get() || CropGrowHandlers.isForcing()) {
            return;
        }
        ClimateBand band = ClimateBands.bandFor(state.getBlock());
        if (band == null || !band.regresses()) {
            return;
        }
        Cause cause = cause(band, reading, CropClimatesConfig.REGRESSION_FIT_THRESHOLD.get());
        if (cause == null || level.getRandom().nextDouble() >= CropClimatesConfig.REGRESSION_CHANCE.get()) {
            return;
        }
        PENDING.computeIfAbsent(level.dimension(), k -> new ArrayList<>())
                .add(new Pending(pos.immutable(), state, cause, band.tree()));
    }

    /** The worse of the two axes, if it is below the threshold. */
    @Nullable
    static Cause cause(ClimateBand band, GrowthGovernor.GrowthReading reading, double threshold) {
        boolean tempBad = reading.fitT() < threshold;
        boolean moistBad = reading.fitM() < threshold;
        if (!tempBad && !moistBad) {
            return null;
        }
        if (tempBad && (!moistBad || reading.fitT() <= reading.fitM())) {
            return reading.tempF() < band.tempLo() ? Cause.COLD : Cause.HOT;
        }
        return reading.conditions().humidity() < band.moistLo() ? Cause.DRY : Cause.WET;
    }

    /** End of the level tick: apply queued wilts to plants that have not changed since. */
    public static void flush(ServerLevel level) {
        List<Pending> pending = PENDING.remove(level.dimension());
        if (pending == null) {
            return;
        }
        for (Pending wilt : pending) {
            BlockState current = level.getBlockState(wilt.pos());
            if (current != wilt.expected()) {
                continue;
            }
            BlockState next = regressed(level, wilt.pos(), current, wilt.tree());
            if (next == null) {
                continue;
            }
            level.setBlock(wilt.pos(), next, next.is(current.getBlock()) ? Block.UPDATE_CLIENTS : Block.UPDATE_ALL);
            level.sendParticles(wilt.cause().particle.get(),
                    wilt.pos().getX() + 0.5, wilt.pos().getY() + 0.6, wilt.pos().getZ() + 0.5,
                    10, 0.3, 0.25, 0.3, 0.0);
            if (!next.is(current.getBlock())) {
                level.playSound(null, wilt.pos(), SoundEvents.GRASS_BREAK, SoundSource.BLOCKS, 0.6F, 0.8F);
            }
        }
    }

    /** One step back, or {@code null} when there is nothing to lose. */
    @Nullable
    static BlockState regressed(Level level, BlockPos pos, BlockState state, boolean tree) {
        if (tree) {
            if (state.hasProperty(BlockStateProperties.STAGE) && state.getValue(BlockStateProperties.STAGE) > 0) {
                return state.setValue(BlockStateProperties.STAGE, state.getValue(BlockStateProperties.STAGE) - 1);
            }
            if (state.hasProperty(BlockStateProperties.HANGING) && state.getValue(BlockStateProperties.HANGING)) {
                return null;
            }
            return level.getBlockState(pos.below()).is(BlockTags.DEAD_BUSH_MAY_PLACE_ON)
                    ? Blocks.DEAD_BUSH.defaultBlockState()
                    : null;
        }
        IntegerProperty age = ageProperty(state);
        if (age == null) {
            return null;
        }
        int value = state.getValue(age);
        int min = age.getPossibleValues().stream().mapToInt(Integer::intValue).min().orElse(0);
        return value > min ? state.setValue(age, value - 1) : null;
    }

    @Nullable
    private static IntegerProperty ageProperty(BlockState state) {
        for (Property<?> property : state.getProperties()) {
            if (property instanceof IntegerProperty integer && "age".equals(property.getName())) {
                return integer;
            }
        }
        return null;
    }

    public static void clear() {
        PENDING.clear();
    }
}
