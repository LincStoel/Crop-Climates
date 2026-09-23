package com.worldtraveler.cropclimates.greenhouse;

import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * One hygrometer as the {@link GreenhouseRegistry} tracks it - it outlives
 * the entity being loaded, so crops keep resolving their room even before
 * the hygrometer itself is back after a restart.
 */
final class Probe {

    final UUID id;
    BlockPos pos;
    /** The block it hangs on, or {@code null} until its entity has reported it. */
    BlockPos support;
    /** The room this hygrometer is in, or {@code null}. */
    Room room;
    GreenhouseStatus status = GreenhouseStatus.SCANNING;
    /**
     * Bounds (walls included) of this hygrometer's own last scan, used to
     * notice block changes that could alter it. {@code null} for a hygrometer
     * that joined a room another hygrometer scans.
     */
    int[] footprint;
    /** Game time this hygrometer should next be scanned; {@code Long.MAX_VALUE} while parked as too large. */
    long dueTick;
    /**
     * Consecutive scans that came back unloaded with nothing changed in
     * between; each doubles the wait before the next retry.
     */
    int misses;
    /**
     * The player who placed or shift-right-clicked this hygrometer, told if
     * its next scan finds the space too large. Not saved.
     */
    @Nullable
    UUID notify;

    Probe(UUID id, BlockPos pos) {
        this.id = id;
        this.pos = pos.immutable();
    }

    boolean isAnchor() {
        return room != null && id.equals(room.anchor);
    }
}
