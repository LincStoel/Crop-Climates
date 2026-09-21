package com.worldtraveler.cropclimates.climate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Queue;
import java.util.Set;

/**
 * Pure flood-fill core of the greenhouse waiver - no Minecraft world access,
 * so it is unit-testable without a level, exactly as {@link GrowthModel} is
 * pure so {@code GrowthModelTest} can pin it against synthetic grids.
 *
 * <p>Driven by the same rule a hearth uses to spread its air (see
 * {@code EnclosureSampler}): a destination cannot be rejected before it is
 * visited, so a solid wall block is enqueued and self-seals on its own pass.
 * That means wall blocks count toward {@link Limits#maxVolume}, not just the
 * open cells inside the room.
 */
public final class EnclosureFlood {

    private EnclosureFlood() {
    }

    @FunctionalInterface
    public interface Steps {
        boolean canStep(BlockPos from, Direction inDir, BlockPos to, Direction outDir);
    }

    public interface Cells {
        boolean isOpen(BlockPos pos);

        boolean seesSky(BlockPos pos);
    }

    public record Limits(int maxVolume, int minVolume, int maxRadius, int maxHeight) {
    }

    public record Result(boolean enclosed, Set<BlockPos> visited) {
    }

    public static Result flood(BlockPos origin, Steps steps, Cells cells, Limits limits) {
        Queue<BlockPos> queue = new ArrayDeque<>();
        Queue<Direction> arrivedFrom = new ArrayDeque<>();
        Set<BlockPos> seen = new LinkedHashSet<>();

        queue.add(origin);
        arrivedFrom.add(Direction.UP);
        seen.add(origin);

        int volume = 0;

        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            Direction inDir = arrivedFrom.poll();

            volume++;
            if (volume > limits.maxVolume()) {
                return new Result(false, seen);
            }
            if (Math.abs(pos.getX() - origin.getX()) > limits.maxRadius()
                    || Math.abs(pos.getZ() - origin.getZ()) > limits.maxRadius()
                    || Math.abs(pos.getY() - origin.getY()) > limits.maxHeight()) {
                return new Result(false, seen);
            }

            if (cells.isOpen(pos) && cells.seesSky(pos)) {
                return new Result(false, seen);
            }

            for (Direction dir : Direction.values()) {
                if (dir == inDir.getOpposite()) {
                    continue;
                }
                BlockPos next = pos.relative(dir);
                if (seen.contains(next)) {
                    continue;
                }
                if (steps.canStep(pos, inDir, next, dir)) {
                    seen.add(next);
                    queue.add(next);
                    arrivedFrom.add(dir);
                }
            }
        }

        return new Result(volume >= limits.minVolume(), seen);
    }
}
