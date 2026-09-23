import me.lucko.spark.proto.SparkSamplerProtos.SamplerData;
import me.lucko.spark.proto.SparkSamplerProtos.StackTraceNode;
import me.lucko.spark.proto.SparkSamplerProtos.ThreadNode;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Summarises a spark {@code .sparkprofile} locally (nothing is uploaded).
 * Run with spark's own jar on the class path, which carries the proto classes:
 * <pre>java -cp spark.jar SparkSummary.java profile.sparkprofile [thread] [--json out.json]</pre>
 *
 * <p>Prints, for the chosen thread (default "Server thread"): total sampled
 * time, the top methods by self time, the top methods by inclusive time
 * (each counted once per stack), and "landmark" frames - crop_climates code,
 * the Cold Sweat calls it makes, and the vanilla callers they sit under.
 */
public class SparkSummary {

    static List<StackTraceNode> all;
    static final Map<String, Double> self = new HashMap<>();
    static final Map<String, Double> inclusive = new HashMap<>();
    static final Map<String, Double> ours = new HashMap<>();

    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0]);
        String threadName = args.length > 1 && !args[1].startsWith("--") ? args[1] : "Server thread";
        String jsonOut = null;
        for (int i = 1; i < args.length - 1; i++) {
            if (args[i].equals("--json")) {
                jsonOut = args[i + 1];
            }
        }
        byte[] bytes = Files.readAllBytes(file);
        SamplerData data;
        try {
            data = SamplerData.parseFrom(bytes);
        } catch (Exception ex) {
            try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
                data = SamplerData.parseFrom(in.readAllBytes());
            }
        }
        ThreadNode thread = null;
        for (ThreadNode t : data.getThreadsList()) {
            if (t.getName().equals(threadName) || (thread == null && t.getName().contains(threadName))) {
                thread = t;
            }
        }
        if (thread == null) {
            System.out.println("threads: " + data.getThreadsList().stream().map(ThreadNode::getName).toList());
            return;
        }
        all = thread.getChildrenList();
        double total = sum(thread.getTimesList());
        for (int ref : thread.getChildrenRefsList()) {
            walk(ref, new HashSet<>(), false, false);
        }
        StringBuilder out = new StringBuilder();
        double tick = inclusive.getOrDefault("net.minecraft.server.MinecraftServer.tickServer", total);
        out.append(String.format(Locale.ROOT, "thread '%s' sampled %.1f ms total, %.1f ms inside tickServer (busy %.1f%%)%n",
                thread.getName(), total, tick, 100 * tick / total));
        out.append(String.format("(percentages below are of time inside tickServer)%n"));
        top(out, "top self time", self, tick, 30);
        top(out, "top inclusive time", inclusive, tick, 45);
        top(out, "crop_climates entry points (outermost frame of our code, inclusive)", ours, tick, 30);
        double oursTotal = ours.values().stream().mapToDouble(Double::doubleValue).sum();
        out.append(String.format(Locale.ROOT, "%nTOTAL in crop_climates code (incl. callees): %.2f ms = %.3f%% of tick time%n",
                oursTotal, 100 * oursTotal / tick));
        System.out.print(out);
        if (jsonOut != null) {
            StringBuilder j = new StringBuilder("{\"thread\":\"" + thread.getName() + "\",\"totalMs\":" + total
                    + ",\"tickMs\":" + tick + ",\"oursMs\":" + oursTotal + ",\"ours\":{");
            boolean first = true;
            for (var e : sorted(ours)) {
                j.append(first ? "" : ",").append('"').append(esc(e.getKey())).append("\":").append(e.getValue());
                first = false;
            }
            j.append("},\"inclusiveTop\":{");
            first = true;
            int n = 0;
            for (var e : sorted(inclusive)) {
                if (n++ >= 80) {
                    break;
                }
                j.append(first ? "" : ",").append('"').append(esc(e.getKey())).append("\":").append(e.getValue());
                first = false;
            }
            j.append("},\"focus\":{");
            first = true;
            for (var e : sorted(inclusive)) {
                String k = e.getKey();
                if (isOurs(k) || k.startsWith("com.momosoftworks.") || FOCUS.contains(k)) {
                    j.append(first ? "" : ",").append('"').append(esc(k)).append("\":").append(e.getValue());
                    first = false;
                }
            }
            j.append("},\"selfTop\":{");
            first = true;
            n = 0;
            for (var e : sorted(self)) {
                if (n++ >= 60) {
                    break;
                }
                j.append(first ? "" : ",").append('"').append(esc(e.getKey())).append("\":").append(e.getValue());
                first = false;
            }
            j.append("}}");
            Files.writeString(Path.of(jsonOut), j.toString());
        }
    }

    static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static boolean isOurs(String key) {
        return key.startsWith("com.worldtraveler.cropclimates.") && !key.startsWith("com.worldtraveler.cropclimates.stress.")
                || key.contains("cropClimates$");
    }

    static final String TICK = "net.minecraft.server.MinecraftServer.tickServer";

    /** Vanilla frames the report compares against. */
    static final Set<String> FOCUS = Set.of(
            "net.minecraft.server.level.ServerLevel.tickChunk",
            "net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase.randomTick",
            "net.minecraft.world.level.block.CropBlock.randomTick",
            "net.neoforged.neoforge.common.CommonHooks.canCropGrow",
            "net.neoforged.neoforge.common.CommonHooks.fireCropGrowPost",
            "net.minecraft.world.level.chunk.LevelChunk.setBlockState",
            "net.minecraft.world.level.Level.setBlock",
            "net.minecraft.world.level.Level.updateNeighborsAt",
            "net.minecraft.server.level.ServerLevel.tickNonPassenger",
            "net.minecraft.world.level.Level.isRainingAt",
            "net.minecraft.world.level.biome.BiomeManager.getBiome",
            "net.minecraft.server.MinecraftServer.saveEverything",
            "net.minecraft.world.level.storage.DimensionDataStorage.save",
            "net.minecraft.world.level.saveddata.SavedData.save");

    /**
     * Depth-first; {@code onStack} holds method keys already on the path so
     * recursion counts once. Only frames inside {@code tickServer} are
     * tallied, so time spent idling between ticks is left out.
     */
    static double walk(int ref, Set<String> onStack, boolean insideOurs, boolean insideTick) {
        StackTraceNode n = all.get(ref);
        double t = sum(n.getTimesList());
        String key = n.getClassName() + "." + n.getMethodName();
        boolean tick = insideTick || key.equals(TICK);
        double children = 0;
        boolean added = onStack.add(key);
        if (added && tick) {
            inclusive.merge(key, t, Double::sum);
        }
        boolean ourFrame = isOurs(key);
        if (ourFrame && !insideOurs && tick) {
            ours.merge(key, t, Double::sum);
        }
        for (int c : n.getChildrenRefsList()) {
            children += walk(c, onStack, insideOurs || ourFrame, tick);
        }
        if (added) {
            onStack.remove(key);
        }
        if (tick) {
            self.merge(key, Math.max(0, t - children), Double::sum);
        }
        return t;
    }

    static double sum(List<Double> xs) {
        double s = 0;
        for (double x : xs) {
            s += x;
        }
        return s;
    }

    static List<Map.Entry<String, Double>> sorted(Map<String, Double> m) {
        List<Map.Entry<String, Double>> list = new ArrayList<>(m.entrySet());
        list.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        return list;
    }

    static void top(StringBuilder out, String title, Map<String, Double> m, double total, int n) {
        out.append(String.format(Locale.ROOT, "%n== %s%n", title));
        int i = 0;
        for (var e : sorted(m)) {
            if (i++ >= n) {
                break;
            }
            out.append(String.format(Locale.ROOT, "%9.1f ms %6.2f%%  %s%n", e.getValue(), 100 * e.getValue() / total, e.getKey()));
        }
    }
}
