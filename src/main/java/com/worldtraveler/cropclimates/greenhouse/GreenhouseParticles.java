package com.worldtraveler.cropclimates.greenhouse;

import com.worldtraveler.cropclimates.CropClimatesConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;

/**
 * Purely cosmetic greenhouse weather. Every {@link #INTERVAL} ticks each
 * greenhouse with a player nearby may drip water from its ceiling when humid,
 * or sift dust from it when dry. The count scales with the room's size, so a
 * big greenhouse drips about as densely as a small one. Only reads the
 * registry's already-scanned cells; never touches growth math.
 */
final class GreenhouseParticles {

    static final int INTERVAL = 5;

    private static final double PLAYER_RANGE = 48.0;
    private static final int MAX_PER_ROUND = 16;
    private static final int MAX_CEILING_CLIMB = 64;
    private static final ParticleOptions DUST =
            new BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.SAND.defaultBlockState());

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
        if (humidity >= CropClimatesConfig.GREENHOUSE_DRIP_HUMIDITY.get()) {
            particle = ParticleTypes.DRIPPING_WATER;
            perSecondPer100 = CropClimatesConfig.GREENHOUSE_DRIP_RATE.get();
        } else if (humidity <= CropClimatesConfig.GREENHOUSE_DUST_HUMIDITY.get()) {
            particle = DUST;
            perSecondPer100 = CropClimatesConfig.GREENHOUSE_DUST_RATE.get();
        } else {
            return;
        }

        RandomSource random = level.getRandom();
        double expected = perSecondPer100 * room.size() / 100.0 * INTERVAL / 20.0;
        int count = (int) expected + (random.nextDouble() < expected - (int) expected ? 1 : 0);
        for (int i = 0; i < Math.min(count, MAX_PER_ROUND); i++) {
            BlockPos top = ceilingAbove(room, BlockPos.of(room.randomCell(random)));
            double x = top.getX() + 0.2 + random.nextDouble() * 0.6;
            double y = top.getY() + 0.95;
            double z = top.getZ() + 0.2 + random.nextDouble() * 0.6;
            level.sendParticles(particle, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    /** The highest interior cell straight above {@code cell} - the one just under the roof. */
    private static BlockPos ceilingAbove(Room room, BlockPos cell) {
        BlockPos.MutableBlockPos pos = cell.mutable();
        for (int i = 0; i < MAX_CEILING_CLIMB && room.containsCell(BlockPos.asLong(pos.getX(), pos.getY() + 1, pos.getZ())); i++) {
            pos.move(0, 1, 0);
        }
        return pos.immutable();
    }
}
