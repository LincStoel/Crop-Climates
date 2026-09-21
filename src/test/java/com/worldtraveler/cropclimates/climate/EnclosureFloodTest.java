package com.worldtraveler.cropclimates.climate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link EnclosureFlood} against synthetic grids - a set of solid cells
 * plus a sky predicate, exactly as {@code GrowthModelTest} pins the pure
 * growth math. No Minecraft world is ever touched.
 *
 * <p>{@link #stepsFollowingOpenCells} models the real self-sealing behaviour
 * from {@code HearthBlockEntity}: a solid destination is enqueued (it cannot
 * be rejected before being visited) but never itself propagates further,
 * because only an open cell's rule permits spreading onward.
 */
class EnclosureFloodTest {

    private static final EnclosureFlood.Limits DEFAULT_LIMITS = new EnclosureFlood.Limits(768, 12, 16, 12);

    /** A room from (-r,0,-r) to (r,h,r) inclusive, walls solid, interior open. */
    private static Set<BlockPos> sealedRoom(int r, int h) {
        Set<BlockPos> solid = new HashSet<>();
        for (int x = -r; x <= r; x++) {
            for (int y = 0; y <= h; y++) {
                for (int z = -r; z <= r; z++) {
                    boolean wall = x == -r || x == r || z == -r || z == r || y == 0 || y == h;
                    if (wall) {
                        solid.add(new BlockPos(x, y, z));
                    }
                }
            }
        }
        return solid;
    }

    private static EnclosureFlood.Cells neverSeesSky(Set<BlockPos> solid) {
        return new EnclosureFlood.Cells() {
            @Override
            public boolean isOpen(BlockPos pos) {
                return !solid.contains(pos);
            }

            @Override
            public boolean seesSky(BlockPos pos) {
                return false;
            }
        };
    }

    /** Only an open cell propagates further; a solid one self-seals. */
    private static EnclosureFlood.Steps stepsFollowingOpenCells(Set<BlockPos> solid) {
        return (from, inDir, to, outDir) -> !solid.contains(from);
    }

    @Test
    void sealedRoomIsEnclosed() {
        // 7x7x5 interior -> walls at radius 3 (x/z -3..3), height 4 (y 0..4)
        Set<BlockPos> solid = sealedRoom(3, 4);
        BlockPos origin = new BlockPos(0, 1, 0);
        EnclosureFlood.Result result = EnclosureFlood.flood(
                origin, stepsFollowingOpenCells(solid), neverSeesSky(solid), DEFAULT_LIMITS);
        assertTrue(result.enclosed());
    }

    @Test
    void wallBlockRemovedEscapes() {
        Set<BlockPos> solid = sealedRoom(3, 4);
        solid.remove(new BlockPos(3, 1, 0)); // punch a hole in a side wall
        BlockPos origin = new BlockPos(0, 1, 0);
        EnclosureFlood.Cells cells = new EnclosureFlood.Cells() {
            @Override
            public boolean isOpen(BlockPos pos) {
                return !solid.contains(pos);
            }

            @Override
            public boolean seesSky(BlockPos pos) {
                return pos.getX() > 3; // sky visible once you step past the old wall
            }
        };
        EnclosureFlood.Result result = EnclosureFlood.flood(
                origin, stepsFollowingOpenCells(solid), cells, DEFAULT_LIMITS);
        assertFalse(result.enclosed());
    }

    @Test
    void roofBlockRemovedSeesSky() {
        Set<BlockPos> solid = sealedRoom(3, 4);
        BlockPos hole = new BlockPos(0, 4, 0);
        solid.remove(hole); // punch a hole in the roof
        BlockPos origin = new BlockPos(0, 1, 0);
        EnclosureFlood.Cells cells = new EnclosureFlood.Cells() {
            @Override
            public boolean isOpen(BlockPos pos) {
                return !solid.contains(pos);
            }

            @Override
            public boolean seesSky(BlockPos pos) {
                return pos.equals(hole) || pos.getY() > 4; // clear straight up from the hole
            }
        };
        EnclosureFlood.Result result = EnclosureFlood.flood(
                origin, stepsFollowingOpenCells(solid), cells, DEFAULT_LIMITS);
        assertFalse(result.enclosed());
    }

    @Test
    void openFieldEscapesImmediately() {
        BlockPos origin = new BlockPos(0, 1, 0);
        EnclosureFlood.Cells openSky = new EnclosureFlood.Cells() {
            @Override
            public boolean isOpen(BlockPos pos) {
                return true;
            }

            @Override
            public boolean seesSky(BlockPos pos) {
                return true;
            }
        };
        EnclosureFlood.Steps steps = (from, inDir, to, outDir) -> true;
        EnclosureFlood.Result result = EnclosureFlood.flood(origin, steps, openSky, DEFAULT_LIMITS);
        assertFalse(result.enclosed());
        assertEquals(1, result.visited().size());
    }

    @Test
    void singleSlabOverOneCropFailsMinVolume() {
        BlockPos slab = new BlockPos(0, 2, 0);
        Set<BlockPos> solid = Set.of(slab);
        BlockPos origin = new BlockPos(0, 1, 0);
        // The crop cell can only step onto the slab; the slab, being solid,
        // self-seals and propagates nowhere - a sealed "volume" of one.
        EnclosureFlood.Steps steps = (from, inDir, to, outDir) -> !solid.contains(from) && to.equals(slab);
        EnclosureFlood.Result result = EnclosureFlood.flood(origin, steps, neverSeesSky(solid), DEFAULT_LIMITS);
        assertFalse(result.enclosed());
        assertTrue(result.visited().size() < DEFAULT_LIMITS.minVolume());
    }

    @Test
    void unboundedCavernFailsMaxVolume() {
        BlockPos origin = new BlockPos(0, 1, 0);
        EnclosureFlood.Cells neverSky = new EnclosureFlood.Cells() {
            @Override
            public boolean isOpen(BlockPos pos) {
                return true;
            }

            @Override
            public boolean seesSky(BlockPos pos) {
                return false;
            }
        };
        EnclosureFlood.Steps steps = (from, inDir, to, outDir) -> true;
        EnclosureFlood.Limits tinyBudget = new EnclosureFlood.Limits(50, 12, 1000, 1000);
        EnclosureFlood.Result result = EnclosureFlood.flood(origin, steps, neverSky, tinyBudget);
        assertFalse(result.enclosed());
    }

    @Test
    void longCorridorFailsMaxRadius() {
        BlockPos origin = new BlockPos(0, 1, 0);
        EnclosureFlood.Cells neverSky = new EnclosureFlood.Cells() {
            @Override
            public boolean isOpen(BlockPos pos) {
                return true;
            }

            @Override
            public boolean seesSky(BlockPos pos) {
                return false;
            }
        };
        // Only allow stepping along +X, simulating a 1-wide corridor that
        // never terminates within maxRadius.
        EnclosureFlood.Steps corridor = (from, inDir, to, outDir) -> outDir == Direction.EAST;
        EnclosureFlood.Limits limits = new EnclosureFlood.Limits(100000, 12, 16, 12);
        EnclosureFlood.Result result = EnclosureFlood.flood(origin, corridor, neverSky, limits);
        assertFalse(result.enclosed());
    }
}
