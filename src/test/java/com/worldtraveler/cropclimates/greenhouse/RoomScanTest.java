package com.worldtraveler.cropclimates.greenhouse;

import com.worldtraveler.cropclimates.climate.EnclosureHumidity;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link RoomScan} against synthetic grids - a set of solid cells plus a
 * sky predicate. No Minecraft world is ever touched.
 *
 * <p>{@link #stepsFollowingOpenCells} models Cold Sweat's hearth spreading: a
 * solid destination is enqueued (it cannot be rejected before being visited)
 * but never itself propagates further.
 */
class RoomScanTest {

    private static final RoomScan.Limits LIMITS = new RoomScan.Limits(4096, 12);
    private static final BlockPos ORIGIN = new BlockPos(0, 1, 0);

    /** A room from (-r,0,-r) to (r,h,r) inclusive, walls solid, interior open. */
    private static Set<BlockPos> sealedRoom(int r, int h) {
        Set<BlockPos> solid = new HashSet<>();
        for (int x = -r; x <= r; x++) {
            for (int y = 0; y <= h; y++) {
                for (int z = -r; z <= r; z++) {
                    if (x == -r || x == r || z == -r || z == r || y == 0 || y == h) {
                        solid.add(new BlockPos(x, y, z));
                    }
                }
            }
        }
        return solid;
    }

    private static RoomScan.Steps stepsFollowingOpenCells(Set<BlockPos> solid) {
        return (from, inDir, to, outDir) -> !solid.contains(from);
    }

    private static RoomScan.Cells cells(Set<BlockPos> solid, Predicate<BlockPos> sky, Set<BlockPos> wet,
                                        Predicate<BlockPos> loaded) {
        return new RoomScan.Cells() {
            @Override
            public boolean isLoaded(BlockPos pos) {
                return loaded.test(pos);
            }

            @Override
            public boolean isOpen(BlockPos pos) {
                return !solid.contains(pos);
            }

            @Override
            public boolean seesSky(BlockPos pos) {
                return sky.test(pos);
            }

            @Override
            public EnclosureHumidity.Effect effect(BlockPos pos) {
                return wet.contains(pos) ? EnclosureHumidity.Effect.HUMIDIFIER : EnclosureHumidity.Effect.NONE;
            }
        };
    }

    private static RoomScan.Cells cells(Set<BlockPos> solid, Predicate<BlockPos> sky) {
        return cells(solid, sky, Set.of(), p -> true);
    }

    private static RoomScan.Status run(RoomScan scan) {
        RoomScan.Status status;
        do {
            status = scan.step(Integer.MAX_VALUE);
        } while (status == RoomScan.Status.RUNNING);
        return status;
    }

    @Test
    void sealedRoomIsEnclosedAndCountsOnlyInteriorCells() {
        Set<BlockPos> solid = sealedRoom(3, 4); // 5x3x5 interior = 75 cells
        RoomScan scan = new RoomScan(ORIGIN, stepsFollowingOpenCells(solid), cells(solid, p -> false), LIMITS);
        assertEquals(RoomScan.Status.ENCLOSED, run(scan));
        assertEquals(5 * 3 * 5, scan.interior().size());
        assertTrue(scan.visited() > scan.interior().size(), "walls are visited but not interior");
        for (BlockPos wall : solid) {
            assertFalse(scan.interior().contains(wall.asLong()));
        }
    }

    @Test
    void resumesAcrossManySmallSteps() {
        Set<BlockPos> solid = sealedRoom(3, 4);
        RoomScan scan = new RoomScan(ORIGIN, stepsFollowingOpenCells(solid), cells(solid, p -> false), LIMITS);
        int calls = 0;
        RoomScan.Status status;
        do {
            status = scan.step(7);
            calls++;
        } while (status == RoomScan.Status.RUNNING);
        assertEquals(RoomScan.Status.ENCLOSED, status);
        assertTrue(calls > 5);
        assertEquals(75, scan.interior().size());
    }

    @Test
    void wallHoleLeaks() {
        Set<BlockPos> solid = sealedRoom(3, 4);
        solid.remove(new BlockPos(3, 1, 0));
        RoomScan scan = new RoomScan(ORIGIN, stepsFollowingOpenCells(solid), cells(solid, p -> p.getX() > 3), LIMITS);
        assertEquals(RoomScan.Status.LEAKED, run(scan));
    }

    @Test
    void roofHoleLeaks() {
        Set<BlockPos> solid = sealedRoom(3, 4);
        BlockPos hole = new BlockPos(0, 4, 0);
        solid.remove(hole);
        RoomScan scan = new RoomScan(ORIGIN, stepsFollowingOpenCells(solid),
                cells(solid, p -> p.equals(hole) || p.getY() > 4), LIMITS);
        assertEquals(RoomScan.Status.LEAKED, run(scan));
    }

    @Test
    void openFieldLeaksImmediately() {
        RoomScan scan = new RoomScan(ORIGIN, (f, i, t, o) -> true, cells(Set.of(), p -> true), LIMITS);
        assertEquals(RoomScan.Status.LEAKED, run(scan));
        assertEquals(1, scan.visited());
    }

    @Test
    void tinyPocketIsTooSmall() {
        Set<BlockPos> solid = sealedRoom(2, 3); // 3x2x3 = 18 interior
        RoomScan scan = new RoomScan(ORIGIN, stepsFollowingOpenCells(solid), cells(solid, p -> false),
                new RoomScan.Limits(4096, 50));
        assertEquals(RoomScan.Status.TOO_SMALL, run(scan));
    }

    @Test
    void cavernIsTooLarge() {
        RoomScan scan = new RoomScan(ORIGIN, (f, i, t, o) -> true, cells(Set.of(), p -> false),
                new RoomScan.Limits(50, 12));
        assertEquals(RoomScan.Status.TOO_LARGE, run(scan));
    }

    @Test
    void unloadedNeighbourDefersInsteadOfLeaking() {
        Set<BlockPos> solid = sealedRoom(3, 4);
        RoomScan scan = new RoomScan(ORIGIN, stepsFollowingOpenCells(solid),
                cells(solid, p -> false, Set.of(), p -> p.getZ() < 3), LIMITS);
        assertEquals(RoomScan.Status.UNLOADED, run(scan));
    }

    @Test
    void wallSpongesAreWeighed() {
        Set<BlockPos> solid = sealedRoom(3, 4);
        Set<BlockPos> sponges = Set.of(new BlockPos(3, 1, 0), new BlockPos(-3, 2, 1));
        RoomScan scan = new RoomScan(ORIGIN, stepsFollowingOpenCells(solid),
                cells(solid, p -> false, sponges, p -> true), LIMITS);
        assertEquals(RoomScan.Status.ENCLOSED, run(scan));
        assertEquals(2, scan.tally().count(EnclosureHumidity.Effect.HUMIDIFIER));
        assertEquals(2, scan.tally().net());
    }

    @Test
    void reachesCoversWallsPlusOne() {
        Set<BlockPos> solid = sealedRoom(3, 4);
        RoomScan scan = new RoomScan(ORIGIN, stepsFollowingOpenCells(solid), cells(solid, p -> false), LIMITS);
        run(scan);
        assertTrue(scan.reaches(new BlockPos(4, 1, 0)));
        assertFalse(scan.reaches(new BlockPos(5, 1, 0)));
    }
}
