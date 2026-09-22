package com.worldtraveler.cropclimates.growth;

import com.worldtraveler.cropclimates.CropClimatesConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Purely cosmetic. Rides the same governed random tick that already produced
 * an {@code ENCLOSED} {@link GrowthGovernor.GrowthReading}, so it costs one
 * short upward scan (capped by {@code greenhouseMaxHeight}) and no extra
 * world state - never anything that affects growth math.
 */
public final class GreenhouseParticles {

    private GreenhouseParticles() {
    }

    public static void maybeSpawn(Level level, BlockPos cropPos, double humidity) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (humidity >= CropClimatesConfig.GREENHOUSE_DRIP_HUMIDITY.get()) {
            if (serverLevel.getRandom().nextDouble() < CropClimatesConfig.GREENHOUSE_DRIP_CHANCE.get()) {
                spawnAtCeiling(serverLevel, cropPos, ParticleTypes.DRIPPING_WATER);
            }
        } else if (humidity <= CropClimatesConfig.GREENHOUSE_DUST_HUMIDITY.get()) {
            if (serverLevel.getRandom().nextDouble() < CropClimatesConfig.GREENHOUSE_DUST_CHANCE.get()) {
                spawnAtCeiling(serverLevel, cropPos, ParticleTypes.CLOUD);
            }
        }
    }

    private static void spawnAtCeiling(ServerLevel level, BlockPos cropPos, ParticleOptions particle) {
        BlockPos ceiling = findCeiling(level, cropPos);
        if (ceiling == null) {
            return;
        }
        double x = ceiling.getX() + 0.5 + (level.getRandom().nextDouble() - 0.5) * 0.6;
        double y = ceiling.getY() + 0.05;
        double z = ceiling.getZ() + 0.5 + (level.getRandom().nextDouble() - 0.5) * 0.6;
        level.sendParticles(particle, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
    }

    /** Highest open cell directly above the crop, i.e. the cell just below the room's ceiling. */
    private static BlockPos findCeiling(ServerLevel level, BlockPos cropPos) {
        BlockPos pos = cropPos.above();
        int maxHeight = CropClimatesConfig.GREENHOUSE_MAX_HEIGHT.get();
        for (int i = 0; i < maxHeight; i++) {
            BlockPos next = pos.above();
            BlockState state = level.getBlockState(next);
            boolean open = state.isAir() || !state.getFluidState().isEmpty();
            if (!open) {
                return pos;
            }
            pos = next;
        }
        return null;
    }
}
