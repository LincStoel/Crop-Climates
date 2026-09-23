package com.worldtraveler.cropclimates.greenhouse;

import com.worldtraveler.cropclimates.CropClimates;
import com.worldtraveler.cropclimates.CropClimatesConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;

/**
 * Purely cosmetic greenhouse weather. Every {@link #INTERVAL} ticks each
 * greenhouse with a player nearby may drip water from its ceiling when humid,
 * or send dust drifting up off its floor when dry. The count scales with the room's size, so a
 * big greenhouse drips about as densely as a small one. Only reads the
 * registry's already-scanned cells; never touches growth math.
 */
final class GreenhouseParticles {

    static final int INTERVAL = 5;

    private static final double PLAYER_RANGE = 48.0;
    private static final int MAX_PER_ROUND = 16;
    private static final int MAX_CLIMB = 64;

    private GreenhouseParticles() {
    }

    static void tick(ServerLevel level, Room room) {
        if (room.size() == 0) {
            return;
        }
        BlockPos anchor = room.anchorPos();
        if (!level.hasNearbyAlivePlayer(anchor.getX() + 0.5, anchor.getY() + 0.5, anchor.getZ() + 0.5, PLAYER_RANGE)) {
            return;
        }

        double humidity = room.humidity(level);
        ParticleOptions particle;
        double perSecondPer100;
        boolean fromCeiling;
        if (humidity >= CropClimatesConfig.GREENHOUSE_DRIP_HUMIDITY.get()) {
            particle = ParticleTypes.DRIPPING_WATER;
            perSecondPer100 = CropClimatesConfig.GREENHOUSE_DRIP_RATE.get();
            fromCeiling = true;
        } else if (humidity <= CropClimatesConfig.GREENHOUSE_DUST_HUMIDITY.get()) {
            particle = CropClimates.GREENHOUSE_DUST.get();
            perSecondPer100 = CropClimatesConfig.GREENHOUSE_DUST_RATE.get();
            fromCeiling = false;
        } else {
            return;
        }

        RandomSource random = level.getRandom();
        double expected = perSecondPer100 * room.size() / 100.0 * INTERVAL / 20.0;
        int count = (int) expected + (random.nextDouble() < expected - (int) expected ? 1 : 0);
        for (int i = 0; i < Math.min(count, MAX_PER_ROUND); i++) {
            BlockPos cell = BlockPos.of(room.randomCell(random));
            BlockPos end = extreme(room, cell, fromCeiling ? 1 : -1);
            double x = end.getX() + 0.2 + random.nextDouble() * 0.6;
            double y = fromCeiling ? end.getY() + 0.95 : end.getY() + 0.1 + random.nextDouble() * 0.3;
            double z = end.getZ() + 0.2 + random.nextDouble() * 0.6;
            level.sendParticles(particle, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    /**
     * The last interior cell straight up ({@code step} 1 - just under the roof)
     * or straight down ({@code step} -1 - just above the floor) from {@code cell}.
     */
    private static BlockPos extreme(Room room, BlockPos cell, int step) {
        BlockPos.MutableBlockPos pos = cell.mutable();
        for (int i = 0; i < MAX_CLIMB && room.containsCell(BlockPos.asLong(pos.getX(), pos.getY() + step, pos.getZ())); i++) {
            pos.move(0, step, 0);
        }
        return pos.immutable();
    }
}
