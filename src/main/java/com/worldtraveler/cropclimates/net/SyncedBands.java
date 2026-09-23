package com.worldtraveler.cropclimates.net;

import net.minecraft.world.level.block.Block;

import java.util.Set;

/**
 * Blocks with a climate band, as last synced from the server. Client side
 * only it is ever filled, so the Jade plugin can skip a server round trip
 * for every block the player looks at that could never show a verdict.
 */
public final class SyncedBands {

    private static volatile Set<Block> blocks = Set.of();

    private SyncedBands() {
    }

    public static boolean has(Block block) {
        return blocks.contains(block);
    }

    public static void set(Set<Block> synced) {
        blocks = Set.copyOf(synced);
    }
}
