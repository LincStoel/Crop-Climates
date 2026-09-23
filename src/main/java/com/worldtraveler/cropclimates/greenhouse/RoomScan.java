package com.worldtraveler.cropclimates.greenhouse;

import com.worldtraveler.cropclimates.climate.EnclosureHumidity;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayDeque;

/**
 * Pure, resumable flood fill behind a hygrometer's greenhouse - no world
 * access, so {@code RoomScanTest} pins it against synthetic grids.
 *
 * <p>Steps follow the same rule a Cold Sweat hearth uses to spread its air: a
 * destination cannot be rejected before it is visited, so a solid wall block
 * is enqueued and self-seals on its own pass. A visited cell that let the fill
 * continue to at least one neighbour is <em>interior</em> - air and anything
 * hearth air passes through, such as crops and farmland (sideways), but not
 * water, which Cold Sweat's default config blacklists - and only interior cells count
 * toward {@link Limits#maxInterior}, get indexed for crop lookups, and are
 * the room size humidity is diluted by. Every visited cell, walls included,
 * is weighed for humidity, since sponges are solid wall blocks.
 *
 * <p>{@link #step} does at most {@code budget} cells per call, so a large room
 * is scanned over several ticks instead of in one spike.
 */
public final class RoomScan {

    public enum Status {
        /** Not finished - call {@link #step} again. */
        RUNNING,
        /** Sealed, and big enough to count. */
        ENCLOSED,
        /** Reached open sky. */
        LEAKED,
        /** Sealed but smaller than {@link Limits#minInterior}. */
        TOO_SMALL,
        /** More interior cells than {@link Limits#maxInterior}. */
        TOO_LARGE,
        /** Touched a chunk that is not loaded - try again later. */
        UNLOADED
    }

    @FunctionalInterface
    public interface Steps {
        boolean canStep(BlockPos from, Direction inDir, BlockPos to, Direction outDir);
    }

    public interface Cells {
        boolean isLoaded(BlockPos pos);

        /** Air or fluid - the only cells whose sky exposure means a leak. */
        boolean isOpen(BlockPos pos);

        boolean seesSky(BlockPos pos);

        /** What the cell does to the room's humidity. */
        EnclosureHumidity.Effect effect(BlockPos pos);
    }

    public record Limits(int maxInterior, int minInterior) {
    }

    private static final Direction[] DIRECTIONS = Direction.values();

    private final BlockPos origin;
    private final Steps steps;
    private final Cells cells;
    private final Limits limits;

    private final ArrayDeque<BlockPos> queue = new ArrayDeque<>();
    private final ArrayDeque<Direction> arrivedFrom = new ArrayDeque<>();
    private final LongOpenHashSet seen = new LongOpenHashSet();
    private final LongOpenHashSet interior = new LongOpenHashSet();
    private final WeightTally tally = new WeightTally();

    private int visited;
    private int minX, minY, minZ, maxX, maxY, maxZ;
    private Status status = Status.RUNNING;

    public RoomScan(BlockPos origin, Steps steps, Cells cells, Limits limits) {
        this.origin = origin.immutable();
        this.steps = steps;
        this.cells = cells;
        this.limits = limits;
        minX = maxX = origin.getX();
        minY = maxY = origin.getY();
        minZ = maxZ = origin.getZ();
        queue.add(this.origin);
        arrivedFrom.add(Direction.UP);
        seen.add(this.origin.asLong());
    }

    public Status step(int budget) {
        if (status != Status.RUNNING) {
            return status;
        }
        for (int done = 0; done < budget; done++) {
            BlockPos pos = queue.poll();
            if (pos == null) {
                return finish(interior.size() >= limits.minInterior() ? Status.ENCLOSED : Status.TOO_SMALL);
            }
            Direction inDir = arrivedFrom.poll();

            if (!cells.isLoaded(pos)) {
                return finish(Status.UNLOADED);
            }
            visited++;
            tally.add(cells.effect(pos));
            if (cells.isOpen(pos) && cells.seesSky(pos)) {
                return finish(Status.LEAKED);
            }

            boolean spreads = false;
            for (Direction dir : DIRECTIONS) {
                if (dir == inDir.getOpposite()) {
                    continue;
                }
                BlockPos next = pos.relative(dir);
                boolean known = seen.contains(next.asLong());
                if (known && spreads) {
                    continue;
                }
                if (!cells.isLoaded(next)) {
                    return finish(Status.UNLOADED);
                }
                if (steps.canStep(pos, inDir, next, dir)) {
                    spreads = true;
                    if (!known) {
                        seen.add(next.asLong());
                        grow(next);
                        queue.add(next);
                        arrivedFrom.add(dir);
                    }
                }
            }

            if (spreads) {
                interior.add(pos.asLong());
                if (interior.size() > limits.maxInterior()) {
                    return finish(Status.TOO_LARGE);
                }
            }
        }
        return status;
    }

    private Status finish(Status result) {
        status = result;
        queue.clear();
        arrivedFrom.clear();
        return result;
    }

    private void grow(BlockPos pos) {
        minX = Math.min(minX, pos.getX());
        minY = Math.min(minY, pos.getY());
        minZ = Math.min(minZ, pos.getZ());
        maxX = Math.max(maxX, pos.getX());
        maxY = Math.max(maxY, pos.getY());
        maxZ = Math.max(maxZ, pos.getZ());
    }

    public Status status() {
        return status;
    }

    public BlockPos origin() {
        return origin;
    }

    /** Interior cells, packed with {@link BlockPos#asLong}. Only complete once finished. */
    public LongOpenHashSet interior() {
        return interior;
    }

    /** Every cell visited so far, walls included. */
    public int visited() {
        return visited;
    }

    public WeightTally tally() {
        return tally;
    }

    /** Whether {@code pos} lies within one block of anything reached so far. */
    public boolean reaches(BlockPos pos) {
        return pos.getX() >= minX - 1 && pos.getX() <= maxX + 1
                && pos.getY() >= minY - 1 && pos.getY() <= maxY + 1
                && pos.getZ() >= minZ - 1 && pos.getZ() <= maxZ + 1;
    }

    /** Bounds of every cell reached so far, walls included: {minX, minY, minZ, maxX, maxY, maxZ}. */
    public int[] bounds() {
        return new int[]{minX, minY, minZ, maxX, maxY, maxZ};
    }

    /** Counts each kind of humidity source among the visited cells. */
    public static final class WeightTally {
        private final int[] counts = new int[EnclosureHumidity.Effect.values().length];
        private int net;

        void add(EnclosureHumidity.Effect effect) {
            counts[effect.ordinal()]++;
            net += EnclosureHumidity.weightOf(effect);
        }

        public int count(EnclosureHumidity.Effect effect) {
            return counts[effect.ordinal()];
        }

        /** Summed {@link EnclosureHumidity#weightOf} over every visited cell. */
        public int net() {
            return net;
        }
    }
}
