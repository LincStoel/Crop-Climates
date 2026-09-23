package com.worldtraveler.cropclimates.stress;

import com.google.gson.JsonObject;
import com.worldtraveler.cropclimates.greenhouse.RoomScan;

/**
 * Diagnostics counters fed by the stress mixins. Plain fields: everything
 * that bumps them runs on the server thread. Hot paths only count; timings
 * are taken on coarse, once-per-tick or rare paths so the harness does not
 * distort what it measures (spark covers the hot paths).
 */
public final class Counters {

    // temperature axis (ClimateSampler)
    public static long tempCalls, tempFreshReads, tempDenied, tempWipes, tempUnavailable;

    // growth hooks
    public static long preCalls, postCalls, extraTicks, vetoes, regressionsApplied;
    public static long readsGoverned, readsUngoverned;

    // greenhouse change detection
    public static long blockChanges, mattersTrue, mattersFalse, neighborNotifies;

    // greenhouse scanning
    public static long cellsScanned, ticksScanning, registryTicks, registryTickNanos, registryTickMaxNanos;
    public static final long[] scansFinished = new long[RoomScan.Status.values().length];
    public static long cellsInFinishedScans, installs, installNanos, installMaxNanos;
    public static long saves, saveNanos, saveMaxNanos, loads, loadNanos;
    public static long particlePackets;

    /** Old -> new block pairs of changes that made a greenhouse rescan (matters() true). */
    public static final java.util.Map<String, Long> rescanCauses = new java.util.HashMap<>();

    /** Cells stepped during the current tick, across dimensions (reset each server tick). */
    public static long cellsThisTick;

    private Counters() {
    }

    public static void reset() {
        tempCalls = tempFreshReads = tempDenied = tempWipes = tempUnavailable = 0;
        preCalls = postCalls = extraTicks = vetoes = regressionsApplied = 0;
        readsGoverned = readsUngoverned = 0;
        blockChanges = mattersTrue = mattersFalse = neighborNotifies = 0;
        cellsScanned = ticksScanning = registryTicks = registryTickNanos = registryTickMaxNanos = 0;
        java.util.Arrays.fill(scansFinished, 0);
        cellsInFinishedScans = installs = installNanos = installMaxNanos = 0;
        saves = saveNanos = saveMaxNanos = loads = loadNanos = 0;
        particlePackets = 0;
        rescanCauses.clear();
    }

    public static JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("tempCalls", tempCalls);
        o.addProperty("tempFreshReads", tempFreshReads);
        o.addProperty("tempDenied", tempDenied);
        o.addProperty("tempWipes", tempWipes);
        o.addProperty("tempUnavailable", tempUnavailable);
        o.addProperty("preCalls", preCalls);
        o.addProperty("postCalls", postCalls);
        o.addProperty("extraTicks", extraTicks);
        o.addProperty("vetoes", vetoes);
        o.addProperty("regressionsApplied", regressionsApplied);
        o.addProperty("readsGoverned", readsGoverned);
        o.addProperty("readsUngoverned", readsUngoverned);
        o.addProperty("blockChanges", blockChanges);
        o.addProperty("mattersTrue", mattersTrue);
        o.addProperty("mattersFalse", mattersFalse);
        o.addProperty("neighborNotifies", neighborNotifies);
        o.addProperty("cellsScanned", cellsScanned);
        o.addProperty("ticksScanning", ticksScanning);
        o.addProperty("registryTicks", registryTicks);
        o.addProperty("registryTickMs", registryTickNanos / 1e6);
        o.addProperty("registryTickMaxMs", registryTickMaxNanos / 1e6);
        JsonObject scans = new JsonObject();
        for (RoomScan.Status s : RoomScan.Status.values()) {
            scans.addProperty(s.name(), scansFinished[s.ordinal()]);
        }
        o.add("scansFinished", scans);
        o.addProperty("cellsInFinishedScans", cellsInFinishedScans);
        o.addProperty("installs", installs);
        o.addProperty("installMs", installNanos / 1e6);
        o.addProperty("installMaxMs", installMaxNanos / 1e6);
        o.addProperty("saves", saves);
        o.addProperty("saveMs", saveNanos / 1e6);
        o.addProperty("saveMaxMs", saveMaxNanos / 1e6);
        o.addProperty("loads", loads);
        o.addProperty("loadMs", loadNanos / 1e6);
        o.addProperty("particlePackets", particlePackets);
        JsonObject causes = new JsonObject();
        rescanCauses.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(20)
                .forEach(e -> causes.addProperty(e.getKey(), e.getValue()));
        o.add("rescanCauses", causes);
        return o;
    }
}
