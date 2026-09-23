package com.worldtraveler.cropclimates.stress;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Forces chunks to load and random-tick without a player. Vanilla only
 * random-ticks chunks within 128 blocks of a non-spectator player; NeoForge
 * ticking tickets ({@code ticking = true}) are the one exception.
 */
public final class ForcedTicks {

    private static final BlockPos OWNER = BlockPos.ZERO;

    private ForcedTicks() {
    }

    /** Forces every chunk touching the block rectangle and loads them now. Returns the chunk count. */
    public static int add(ServerLevel level, int x1, int z1, int x2, int z2) {
        int cx1 = Math.min(x1, x2) >> 4, cx2 = Math.max(x1, x2) >> 4;
        int cz1 = Math.min(z1, z2) >> 4, cz2 = Math.max(z1, z2) >> 4;
        int n = 0;
        for (int cx = cx1; cx <= cx2; cx++) {
            for (int cz = cz1; cz <= cz2; cz++) {
                StressMod.TICKETS.forceChunk(level, OWNER, cx, cz, true, true);
                n++;
            }
        }
        for (int cx = cx1; cx <= cx2; cx++) {
            for (int cz = cz1; cz <= cz2; cz++) {
                level.getChunk(cx, cz, ChunkStatus.FULL, true);
            }
        }
        return n;
    }

    public static int remove(ServerLevel level, int x1, int z1, int x2, int z2) {
        int cx1 = Math.min(x1, x2) >> 4, cx2 = Math.max(x1, x2) >> 4;
        int cz1 = Math.min(z1, z2) >> 4, cz2 = Math.max(z1, z2) >> 4;
        int n = 0;
        for (int cx = cx1; cx <= cx2; cx++) {
            for (int cz = cz1; cz <= cz2; cz++) {
                StressMod.TICKETS.forceChunk(level, OWNER, cx, cz, false, true);
                n++;
            }
        }
        return n;
    }
}
