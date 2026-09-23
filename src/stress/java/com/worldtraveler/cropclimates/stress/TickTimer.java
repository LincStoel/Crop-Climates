package com.worldtraveler.cropclimates.stress;

import com.google.gson.JsonObject;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Arrays;

/**
 * Wall time of every server tick, bracketed by {@code ServerTickEvent.Pre}
 * at HIGHEST and {@code .Post} at LOWEST priority, so it covers everything
 * {@code tickServer} does - level ticks, entity ticks, autosave. Recording
 * windows produce percentile stats plus a per-tick CSV.
 */
public final class TickTimer {

    private static long tickStart;
    public static long lastTickNanos;
    public static long serverTicks;

    private static boolean recording;
    private static String label = "";
    private static long[] samples = new long[1 << 16];
    private static int count;
    private static long windowStartNanos;
    private static long windowStartTick;

    private TickTimer() {
    }

    public static void onPre(ServerTickEvent.Pre event) {
        tickStart = System.nanoTime();
        Counters.cellsThisTick = 0;
    }

    public static void onPost(ServerTickEvent.Post event) {
        long d = System.nanoTime() - tickStart;
        lastTickNanos = d;
        serverTicks++;
        if (Counters.cellsThisTick > 0) {
            Counters.ticksScanning++;
        }
        if (recording) {
            if (count == samples.length) {
                samples = Arrays.copyOf(samples, count * 2);
            }
            samples[count++] = d;
        }
    }

    public static boolean isRecording() {
        return recording;
    }

    public static void begin(String newLabel) {
        label = newLabel;
        count = 0;
        recording = true;
        windowStartNanos = System.nanoTime();
        windowStartTick = serverTicks;
        Counters.reset();
        Results.log("mspt start " + label);
    }

    /** Ends the window and returns its stats; also writes {@code <label>-ticks.csv}. */
    public static JsonObject end() {
        recording = false;
        double wallSeconds = (System.nanoTime() - windowStartNanos) / 1e9;
        long[] sorted = Arrays.copyOf(samples, count);
        StringBuilder csv = new StringBuilder("tick,ms\n");
        for (int i = 0; i < count; i++) {
            csv.append(i).append(',').append(String.format(java.util.Locale.ROOT, "%.4f", samples[i] / 1e6)).append('\n');
        }
        Results.writeText(label + "-ticks.csv", csv.toString());
        Arrays.sort(sorted);
        JsonObject o = new JsonObject();
        o.addProperty("label", label);
        o.addProperty("ticks", count);
        o.addProperty("wallSeconds", wallSeconds);
        o.addProperty("tps", wallSeconds > 0 ? count / wallSeconds : 0);
        if (count > 0) {
            long sum = 0;
            int over50 = 0;
            int over25 = 0;
            for (long s : sorted) {
                sum += s;
                if (s > 50_000_000L) {
                    over50++;
                }
                if (s > 25_000_000L) {
                    over25++;
                }
            }
            o.addProperty("meanMs", sum / (double) count / 1e6);
            o.addProperty("p50Ms", pct(sorted, 0.50));
            o.addProperty("p90Ms", pct(sorted, 0.90));
            o.addProperty("p95Ms", pct(sorted, 0.95));
            o.addProperty("p99Ms", pct(sorted, 0.99));
            o.addProperty("p999Ms", pct(sorted, 0.999));
            o.addProperty("maxMs", sorted[count - 1] / 1e6);
            o.addProperty("over25ms", over25);
            o.addProperty("over50ms", over50);
        }
        o.add("counters", Counters.toJson());
        Results.log("mspt stop " + label + " " + Results.compact(o));
        return o;
    }

    private static double pct(long[] sorted, double p) {
        int idx = (int) Math.min(sorted.length - 1, Math.max(0, Math.ceil(p * sorted.length) - 1));
        return sorted[idx] / 1e6;
    }

    public static String label() {
        return label;
    }

    public static long windowTicks() {
        return serverTicks - windowStartTick;
    }
}
