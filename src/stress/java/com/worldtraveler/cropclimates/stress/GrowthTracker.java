package com.worldtraveler.cropclimates.stress;

import com.google.gson.JsonObject;
import com.worldtraveler.cropclimates.climate.ClimateBands;
import com.worldtraveler.cropclimates.growth.GrowthGovernor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Measures actual growth in a box, per plant type, alongside what
 * {@link GrowthGovernor} predicts for the same plants - the functional check
 * that the multiplier the Soil Tester shows is the rate plants really grow at.
 *
 * <p>Growth units: one per age step for staged plants; one per stage step
 * (and one more for becoming a tree) for saplings; for sugar cane and cactus
 * {@code 16 * new blocks + change in the top block's age}, so every
 * successful growth tick counts as one; for bamboo, one per new block.
 * Growth is scored where the game scores it: the plant, the top of a cane
 * or bamboo column, or the cell above a cactus.
 */
public final class GrowthTracker {

    private enum Kind { AGE, SAPLING, STACK_AGE, STACK }

    private record Entry(long pos, Block block, Kind kind, int value, int top) {
    }

    private record Snapshot(ServerLevel level, long tick, int randomTickSpeed, List<Entry> entries,
                            Map<Block, double[]> predicted) {
    }

    private static final Map<String, Snapshot> SNAPSHOTS = new HashMap<>();

    private GrowthTracker() {
    }

    public static int track(String label, ServerLevel level, BlockPos a, BlockPos b) {
        List<Entry> entries = new ArrayList<>();
        Map<Block, double[]> predicted = new HashMap<>();
        for (BlockPos pos : BlockPos.betweenClosed(a, b)) {
            BlockState state = level.getBlockState(pos);
            Block block = state.getBlock();
            if (ClimateBands.bandFor(block) == null) {
                continue;
            }
            Kind kind = kindOf(state);
            BlockPos scored = pos;
            int value;
            int top = 0;
            if (kind == Kind.STACK || kind == Kind.STACK_AGE) {
                if (level.getBlockState(pos.below()).is(block)) {
                    continue; // only column bases are tracked
                }
                int h = height(level, pos, block);
                BlockPos topPos = pos.above(h - 1);
                value = h;
                top = kind == Kind.STACK_AGE ? level.getBlockState(topPos).getValue(BlockStateProperties.AGE_15) : 0;
                scored = block instanceof CactusBlock ? topPos.above() : topPos;
            } else if (kind == Kind.SAPLING) {
                value = state.hasProperty(BlockStateProperties.STAGE) ? state.getValue(BlockStateProperties.STAGE) : 0;
            } else {
                IntegerProperty age = PlantType.ageProperty(state);
                if (age == null) {
                    continue;
                }
                value = state.getValue(age);
            }
            entries.add(new Entry(pos.asLong(), block, kind, value, top));
            GrowthGovernor.GrowthReading reading = GrowthGovernor.read(level, scored, state);
            double[] p = predicted.computeIfAbsent(block, k -> new double[]{0, 0, Double.MAX_VALUE, 0, 0});
            if (reading == null) {
                p[4]++;
            } else {
                p[0] += reading.total();
                p[1]++;
                p[2] = Math.min(p[2], reading.total());
                p[3] = Math.max(p[3], reading.total());
            }
        }
        int rts = level.getGameRules().getInt(GameRules.RULE_RANDOMTICKING);
        SNAPSHOTS.put(label, new Snapshot(level, level.getGameTime(), rts, entries, predicted));
        Results.log("growth track " + label + " " + entries.size() + " plants");
        return entries.size();
    }

    public static JsonObject report(String label) {
        Snapshot snap = SNAPSHOTS.get(label);
        JsonObject out = new JsonObject();
        if (snap == null) {
            out.addProperty("error", "no snapshot " + label);
            return out;
        }
        ServerLevel level = snap.level();
        Map<String, double[]> perType = new TreeMap<>(); // n, units, maxed, advanced
        for (Entry e : snap.entries()) {
            BlockPos pos = BlockPos.of(e.pos());
            BlockState now = level.getBlockState(pos);
            double units = 0;
            boolean maxed = false;
            switch (e.kind()) {
                case AGE -> {
                    if (now.is(e.block())) {
                        IntegerProperty age = PlantType.ageProperty(now);
                        int v = now.getValue(age);
                        units = Math.max(0, v - e.value());
                        maxed = v == age.getPossibleValues().stream().mapToInt(Integer::intValue).max().orElse(v);
                    } else if (!now.isAir()) {
                        // Matured into another block: a torchflower crop becomes a torchflower,
                        // a stem that fruited becomes an attached stem. Count it as fully grown.
                        IntegerProperty age = PlantType.ageProperty(e.block().defaultBlockState());
                        int max = age == null ? e.value() : age.getPossibleValues().stream().mapToInt(Integer::intValue).max().orElse(e.value());
                        units = Math.max(0, max - e.value());
                        maxed = true;
                    }
                }
                case SAPLING -> {
                    if (now.is(e.block())) {
                        int v = now.hasProperty(BlockStateProperties.STAGE) ? now.getValue(BlockStateProperties.STAGE) : 0;
                        units = Math.max(0, v - e.value());
                    } else if (!now.isAir()) {
                        units = 2 - e.value(); // grew into a tree
                        maxed = true;
                    }
                }
                case STACK, STACK_AGE -> {
                    int h = now.is(e.block()) ? height(level, pos, e.block()) : 0;
                    units = h - e.value();
                    if (e.kind() == Kind.STACK_AGE && h > 0) {
                        int topAge = level.getBlockState(pos.above(h - 1)).getValue(BlockStateProperties.AGE_15);
                        units = 16.0 * (h - e.value()) + (topAge - e.top());
                    }
                    maxed = h >= 3;
                }
            }
            String id = BuiltInRegistries.BLOCK.getKey(e.block()).toString();
            double[] t = perType.computeIfAbsent(id, k -> new double[4]);
            t[0]++;
            t[1] += units;
            if (maxed) {
                t[2]++;
            }
            if (units > 0) {
                t[3]++;
            }
        }
        long ticks = level.getGameTime() - snap.tick();
        out.addProperty("label", label);
        out.addProperty("ticks", ticks);
        out.addProperty("randomTickSpeed", snap.randomTickSpeed());
        JsonObject types = new JsonObject();
        for (Map.Entry<String, double[]> t : perType.entrySet()) {
            JsonObject o = new JsonObject();
            double[] v = t.getValue();
            o.addProperty("plants", (long) v[0]);
            o.addProperty("units", v[1]);
            o.addProperty("unitsPerPlant", v[1] / v[0]);
            o.addProperty("maxedOut", (long) v[2]);
            // Saturation-free rate of the first growth event: -ln(1 - share that advanced).
            o.addProperty("advanced", (long) v[3]);
            o.addProperty("firstEventRate", v[3] < v[0] ? -Math.log(1 - v[3] / v[0]) : Double.POSITIVE_INFINITY);
            Block block = BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse(t.getKey()));
            double[] p = snap.predicted().get(block);
            if (p != null) {
                o.addProperty("predictedMean", p[1] > 0 ? p[0] / p[1] : Double.NaN);
                o.addProperty("predictedMin", p[1] > 0 ? p[2] : Double.NaN);
                o.addProperty("predictedMax", p[1] > 0 ? p[3] : Double.NaN);
                o.addProperty("predictedUnavailable", (long) p[4]);
            }
            types.add(t.getKey(), o);
        }
        out.add("types", types);
        Results.writeJson("growth-" + label, out);
        return out;
    }

    private static Kind kindOf(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof SugarCaneBlock || block instanceof CactusBlock) {
            return Kind.STACK_AGE;
        }
        if (block instanceof BambooStalkBlock) {
            return Kind.STACK;
        }
        if (block instanceof SaplingBlock || ClimateBands.isSapling(block)) {
            return Kind.SAPLING;
        }
        return Kind.AGE;
    }

    private static int height(ServerLevel level, BlockPos base, Block block) {
        int h = 0;
        while (h < 64 && level.getBlockState(base.above(h)).is(block)) {
            h++;
        }
        return h;
    }
}
