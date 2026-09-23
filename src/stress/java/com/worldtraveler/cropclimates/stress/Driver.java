package com.worldtraveler.cropclimates.stress;

import com.google.gson.JsonObject;
import com.worldtraveler.cropclimates.greenhouse.GreenhouseStatus;
import com.worldtraveler.cropclimates.greenhouse.Greenhouses;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Per-tick harness work: block churn and greenhouse latency probes. */
public final class Driver {

    /** How many blocks a churn task changes per tick, where, and how. */
    public record Churn(String mode, ServerLevel level, List<Builders.Greenhouse> rooms, BlockPos a, BlockPos b,
                        int perTick, RandomSource random) {
    }

    private static final List<Churn> CHURN = new ArrayList<>();

    private enum Phase { LEAK, RESEAL }

    private static final class Latency {
        ServerLevel level;
        UUID hygrometer;
        BlockPos roof;
        BlockState roofState;
        Phase phase = Phase.LEAK;
        long start;
        long leakTicks = -1;
        long resealTicks = -1;
    }

    @Nullable
    private static Latency latency;
    @Nullable
    public static JsonObject lastLatency;

    private Driver() {
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        for (Churn churn : CHURN) {
            runChurn(churn);
        }
        if (latency != null) {
            pollLatency();
        }
    }

    // ------------------------------------------------------------------ churn

    public static void startChurn(Churn churn) {
        CHURN.add(churn);
        Results.log("churn start " + churn.mode() + " x" + churn.perTick());
    }

    public static int stopChurn() {
        int n = CHURN.size();
        CHURN.clear();
        return n;
    }

    private static void runChurn(Churn c) {
        if (c.mode().equals("click")) {
            // A player right-clicking every hygrometer of the set once per `perTick` ticks: each click asks for a rescan.
            if (c.level().getGameTime() % c.perTick() == 0) {
                for (Builders.Greenhouse g : c.rooms()) {
                    Greenhouses.get(c.level()).requestScan(g.hygrometer(), c.level().getGameTime());
                }
            }
            return;
        }
        for (int i = 0; i < c.perTick(); i++) {
            switch (c.mode()) {
                case "crops" -> {
                    // Harvest and replant: a grown crop is cut, an empty farmland cell is sown.
                    Builders.Greenhouse g = c.rooms().get(c.random().nextInt(c.rooms().size()));
                    BlockPos p = new BlockPos(g.x0() + 2 + c.random().nextInt(Math.max(1, g.ix() - 1)), g.y() + 1,
                            g.z0() + 1 + c.random().nextInt(g.iz()));
                    BlockState s = c.level().getBlockState(p);
                    if (s.getBlock() instanceof CropBlock) {
                        c.level().setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    } else if (s.isAir() && c.level().getBlockState(p.below()).is(Blocks.FARMLAND)) {
                        c.level().setBlock(p, Blocks.WHEAT.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
                case "walls" -> {
                    // A glass block appears or disappears just under the roof.
                    Builders.Greenhouse g = c.rooms().get(c.random().nextInt(c.rooms().size()));
                    BlockPos p = new BlockPos(g.x0() + 2 + c.random().nextInt(Math.max(1, g.ix() - 1)), g.y() + g.iy() - 1,
                            g.z0() + 1 + c.random().nextInt(g.iz()));
                    BlockState s = c.level().getBlockState(p);
                    c.level().setBlock(p, s.isAir() ? Blocks.GLASS.defaultBlockState() : Blocks.AIR.defaultBlockState(),
                            Block.UPDATE_ALL);
                }
                case "far" -> {
                    // Stone <-> air with neighbour updates, nowhere near a greenhouse.
                    int x = c.a().getX() + c.random().nextInt(c.b().getX() - c.a().getX() + 1);
                    int y = c.a().getY() + c.random().nextInt(c.b().getY() - c.a().getY() + 1);
                    int z = c.a().getZ() + c.random().nextInt(c.b().getZ() - c.a().getZ() + 1);
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState s = c.level().getBlockState(p);
                    c.level().setBlock(p, s.isAir() ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(),
                            Block.UPDATE_ALL);
                }
                default -> {
                }
            }
        }
    }

    // ---------------------------------------------------------------- latency

    /**
     * Opens the roof block above a hygrometer's room and times how long the
     * registry takes to report the leak, then closes it and times the reseal.
     */
    public static String startLatency(ServerLevel level, UUID hygrometer, BlockPos roof) {
        Latency l = new Latency();
        l.level = level;
        l.hygrometer = hygrometer;
        l.roof = roof.immutable();
        l.roofState = level.getBlockState(roof);
        l.start = level.getGameTime();
        level.setBlock(roof, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        latency = l;
        lastLatency = null;
        return "opened roof at " + roof.toShortString() + " (was " + l.roofState.getBlock().getName().getString() + ")";
    }

    private static void pollLatency() {
        Latency l = latency;
        long now = l.level.getGameTime();
        GreenhouseStatus status = Greenhouses.get(l.level).statusOf(l.hygrometer);
        if (l.phase == Phase.LEAK && status != GreenhouseStatus.GREENHOUSE && status != GreenhouseStatus.SCANNING) {
            l.leakTicks = now - l.start;
            l.level.setBlock(l.roof, l.roofState, Block.UPDATE_ALL);
            l.phase = Phase.RESEAL;
            l.start = now;
        } else if (l.phase == Phase.RESEAL && status == GreenhouseStatus.GREENHOUSE) {
            l.resealTicks = now - l.start;
            finishLatency("ok");
        } else if (now - l.start > 36_000) {
            if (l.phase == Phase.LEAK) {
                l.level.setBlock(l.roof, l.roofState, Block.UPDATE_ALL);
            }
            finishLatency("timeout in " + l.phase);
        }
    }

    private static void finishLatency(String result) {
        JsonObject o = new JsonObject();
        o.addProperty("result", result);
        o.addProperty("leakTicks", latency.leakTicks);
        o.addProperty("resealTicks", latency.resealTicks);
        lastLatency = o;
        Results.log("latency " + Results.compact(o));
        latency = null;
    }

    public static boolean latencyRunning() {
        return latency != null;
    }
}
