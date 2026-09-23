package com.worldtraveler.cropclimates;

import com.mojang.logging.LogUtils;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Tuning constants. A SERVER config, only ever read on the logical server.
 * NeoForge 21.1 keeps it in {@code config/crop_climates-server.toml}; a copy
 * in a world's {@code serverconfig/} folder overrides it for that world.
 */
public final class CropClimatesConfig {

    public enum Units { F, C }

    /**
     * No setting makes a greenhouse bigger than this many interior cells.
     * Finishing a rescan updates the room's cells within a single tick; on the
     * stress server that cost 3-7 ms per 140,000 cells, so a room this size
     * costs roughly 6-13 ms in that tick. Scans themselves are spread over
     * ticks, but every other greenhouse waits behind a big room's rescan.
     */
    public static final int GREENHOUSE_VOLUME_CEILING = 262_144;

    /** Tallest greenhouse setting: one short of the overworld's 384-block height. */
    public static final int GREENHOUSE_HEIGHT_LIMIT = 383;

    private static volatile boolean warnedCeiling;

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.DoubleValue GROWTH_FLOOR;
    public static final ModConfigSpec.DoubleValue GROWTH_MAX;
    public static final ModConfigSpec.DoubleValue GROWTH_CURVE;

    public static final ModConfigSpec.DoubleValue TEMP_TOLERANCE;
    public static final ModConfigSpec.DoubleValue MOIST_TOLERANCE;
    public static final ModConfigSpec.DoubleValue TREE_FORGIVENESS;

    public static final ModConfigSpec.DoubleValue SKY_PENALTY;
    public static final ModConfigSpec.DoubleValue DEFAULT_BIOME_MOISTURE;
    public static final ModConfigSpec.EnumValue<Units> DISPLAY_UNITS;

    public static final ModConfigSpec.DoubleValue RAIN_HUMIDITY_SHIFT;

    public static final ModConfigSpec.BooleanValue REGRESSION_ENABLED;
    public static final ModConfigSpec.DoubleValue REGRESSION_FIT_THRESHOLD;
    public static final ModConfigSpec.DoubleValue REGRESSION_CHANCE;

    public static final ModConfigSpec.IntValue TEMP_CACHE_TTL;
    public static final ModConfigSpec.IntValue TEMP_CACHE_CAP;
    public static final ModConfigSpec.IntValue TEMP_READS_PER_TICK;
    public static final ModConfigSpec.IntValue ERROR_LIMIT;

    public static final ModConfigSpec.BooleanValue GREENHOUSE_ENABLED;
    public static final ModConfigSpec.IntValue GREENHOUSE_MAX_RADIUS;
    public static final ModConfigSpec.IntValue GREENHOUSE_MAX_HEIGHT;
    public static final ModConfigSpec.IntValue GREENHOUSE_MIN_VOLUME;
    public static final ModConfigSpec.IntValue GREENHOUSE_SKY_SCAN;
    public static final ModConfigSpec.IntValue GREENHOUSE_CELLS_PER_TICK;
    public static final ModConfigSpec.IntValue GREENHOUSE_RESCAN_INTERVAL;
    public static final ModConfigSpec.IntValue GREENHOUSE_UNSEALED_RETRY_INTERVAL;
    public static final ModConfigSpec.IntValue GREENHOUSE_CHANGE_DELAY;
    public static final ModConfigSpec.DoubleValue HUMIDITY_BLOCK_MULTIPLIER;

    public static final ModConfigSpec.DoubleValue GREENHOUSE_DRIP_HUMIDITY;
    public static final ModConfigSpec.DoubleValue GREENHOUSE_DRIP_RATE;
    public static final ModConfigSpec.DoubleValue GREENHOUSE_DUST_HUMIDITY;
    public static final ModConfigSpec.DoubleValue GREENHOUSE_DUST_RATE;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("growth");
        GROWTH_FLOOR = builder
                .comment("Slowest a plant can ever grow (multiplier).")
                .defineInRange("growthFloor", 0.005, 0.0, 10.0);
        GROWTH_MAX = builder
                .comment("Fastest a plant can ever grow, at the dead centre of both bands.")
                .defineInRange("growthMax", 1.25, 0.0, 10.0);
        GROWTH_CURVE = builder
                .comment("Exponent applied to the combined fit. 1.0 = proportional; >1 stacks extra punishment on top of falloff.")
                .defineInRange("growthCurve", 1.0, 0.01, 10.0);
        builder.pop();

        builder.push("tolerance");
        TEMP_TOLERANCE = builder
                .comment("Halving distance in degrees F outside the temperature band.")
                .defineInRange("tempTolerance", 15.0, 0.01, 1000.0);
        MOIST_TOLERANCE = builder
                .comment("Halving distance (0-1 scale) outside the humidity band.")
                .defineInRange("moistTolerance", 0.10, 0.001, 1.0);
        TREE_FORGIVENESS = builder
                .comment("Multiplies both tolerances above, saplings only.")
                .defineInRange("treeForgiveness", 1.3, 1.0, 100.0);
        builder.pop();

        builder.push("misc");
        SKY_PENALTY = builder
                .comment("Flat multiplier applied when the crop cannot see the sky.")
                .defineInRange("skyPenalty", 0.85, 0.0, 1.0);
        DEFAULT_BIOME_MOISTURE = builder
                .comment("Moisture used for a biome that cannot be resolved at all (should not happen in practice).")
                .defineInRange("defaultBiomeMoisture", 0.4, 0.0, 1.0);
        DISPLAY_UNITS = builder
                .comment("Temperature unit for Soil Tester and Hygrometer reports when a player's own Cold Sweat unit preference cannot be read.")
                .defineEnum("displayUnits", Units.F);
        builder.pop();

        builder.push("weather");
        RAIN_HUMIDITY_SHIFT = builder
                .comment("Added to humidity for as long as rain is actually falling on a crop (or an outdoor Hygrometer). " +
                        "Roofs, glass included, and sealed greenhouses block it.")
                .defineInRange("rainHumidityShift", 0.15, 0.0, 1.0);
        builder.pop();

        builder.push("regression");
        REGRESSION_ENABLED = builder
                .comment("Whether plants far outside their bands slowly lose growth stages (saplings eventually die to a dead bush).")
                .define("regressionEnabled", true);
        REGRESSION_FIT_THRESHOLD = builder
                .comment("A plant regresses only while its temperature or humidity fit is below this (1.0 = ideal; 0.25 is two halvings outside the band).")
                .defineInRange("regressionFitThreshold", 0.25, 0.0, 1.0);
        REGRESSION_CHANCE = builder
                .comment("Chance per failed governed random tick of losing one stage. 0.02 is roughly one stage per in-game hour.")
                .defineInRange("regressionChance", 0.02, 0.0, 1.0);
        builder.pop();

        builder.push("performance");
        TEMP_CACHE_TTL = builder
                .comment("Ticks a cached temperature reading stays valid. Also what lets day/night reach crops.")
                .defineInRange("tempCacheTtl", 200, 1, Integer.MAX_VALUE);
        TEMP_CACHE_CAP = builder
                .comment("Temperature cells (4x8x4 blocks each) kept cached. At the cap, cells unused for a while are dropped first, " +
                        "then the least recently used half. Keep it above the number of cells with growing plants: an uncached " +
                        "cell over the read budget goes unscored. Roughly 100 bytes each.")
                .defineInRange("tempCacheCap", 65536, 1, Integer.MAX_VALUE);
        TEMP_READS_PER_TICK = builder
                .comment("Fresh Cold Sweat temperature reads allowed per game tick; over budget uses a stale reading, " +
                        "and a cell with none yet is left unscored for that tick.")
                .defineInRange("tempReadsPerTick", 12, 1, Integer.MAX_VALUE);
        ERROR_LIMIT = builder
                .comment("Logged failures before climate growth disables itself for the session and reverts to vanilla.")
                .defineInRange("errorLimit", 8, 1, Integer.MAX_VALUE);
        builder.pop();

        builder.push("greenhouse");
        GREENHOUSE_ENABLED = builder
                .comment("Whether a Hygrometer in a sealed room turns it into a greenhouse whose humidity replaces the biome's.")
                .define("greenhouseEnabled", true);
        GREENHOUSE_MAX_RADIUS = builder
                .comment("Together with greenhouseMaxHeight, sets the largest greenhouse: a room may hold at most " +
                        "(2 * radius + 1)^2 * height interior cells (air, water, crops - not walls), and never more than " +
                        GREENHOUSE_VOLUME_CEILING + " whatever these are set to. Bigger rooms read as too large. " +
                        "Only the cell count is capped; the room's shape is free.")
                .defineInRange("greenhouseMaxRadius", 32, 1, 1024);
        GREENHOUSE_MAX_HEIGHT = builder
                .comment("See greenhouseMaxRadius. Large spruce trees grow to a max of 32 blocks tall for reference. " +
                        "At most " + GREENHOUSE_HEIGHT_LIMIT + ", one short of the world's height.")
                .defineInRange("greenhouseMaxHeight", 34, 1, GREENHOUSE_HEIGHT_LIMIT);
        GREENHOUSE_MIN_VOLUME = builder
                .comment("Smallest sealed room, in interior cells, that counts as a greenhouse.")
                .defineInRange("greenhouseMinVolume", 12, 1, Integer.MAX_VALUE);
        GREENHOUSE_SKY_SCAN = builder
                .comment("Blocks scanned upward per sky test. Matches the hearth's own value.")
                .defineInRange("greenhouseSkyScan", 64, 1, Integer.MAX_VALUE);
        GREENHOUSE_CELLS_PER_TICK = builder
                .comment("Cells all greenhouse scans in a dimension may visit per tick. Large rooms finish over several ticks.")
                .defineInRange("greenhouseCellsPerTick", 2048, 1, Integer.MAX_VALUE);
        GREENHOUSE_RESCAN_INTERVAL = builder
                .comment("Ticks between routine rescans of a greenhouse, as a backstop to change detection.")
                .defineInRange("greenhouseRescanInterval", 1200, 20, Integer.MAX_VALUE);
        GREENHOUSE_UNSEALED_RETRY_INTERVAL = builder
                .comment("Ticks between retries for a Hygrometer that is not in a sealed room.")
                .defineInRange("greenhouseUnsealedRetryInterval", 600, 20, Integer.MAX_VALUE);
        GREENHOUSE_CHANGE_DELAY = builder
                .comment("Ticks to wait after a block changes in or around a greenhouse before rescanning, so a burst of changes costs one scan.")
                .defineInRange("greenhouseChangeDelay", 20, 1, Integer.MAX_VALUE);
        HUMIDITY_BLOCK_MULTIPLIER = builder
                .comment("Scale applied to net water/lava/desiccant/humidifier weight before dividing by " +
                        "the room's interior size (walls not counted) and adding to biome humidity. " +
                        "Water/humidifier +1, desiccant -1, lava -3. Sources in the walls still count.")
                .defineInRange("humidityBlockMultiplier", 4.0, 0.0, 100.0);
        GREENHOUSE_DRIP_HUMIDITY = builder
                .comment("Humidity at/above which water drips from a greenhouse's ceiling.")
                .defineInRange("greenhouseDripHumidity", 0.9, 0.0, 1.0);
        GREENHOUSE_DRIP_RATE = builder
                .comment("Drips per second for every 100 interior cells of a greenhouse at/above the drip humidity, " +
                        "falling from random spots on its ceiling. Only spawned with a player nearby.")
                .defineInRange("greenhouseDripRate", 0.5, 0.0, 100.0);
        GREENHOUSE_DUST_HUMIDITY = builder
                .comment("Humidity at/below which dust drifts up off a greenhouse's floor.")
                .defineInRange("greenhouseDustHumidity", 0.1, 0.0, 1.0);
        GREENHOUSE_DUST_RATE = builder
                .comment("Dust motes per second for every 100 interior cells of a greenhouse at/below the dust humidity, " +
                        "rising slowly from random spots on its floor. Only spawned with a player nearby.")
                .defineInRange("greenhouseDustRate", 0.25, 0.0, 100.0);
        builder.pop();

        SPEC = builder.build();
    }

    /**
     * Largest greenhouse in interior cells: {@code (2r + 1)^2 * h} from the
     * radius and height settings, but never over {@link #GREENHOUSE_VOLUME_CEILING}.
     */
    public static int greenhouseMaxVolume() {
        long side = 2L * GREENHOUSE_MAX_RADIUS.get() + 1;
        long configured = side * side * GREENHOUSE_MAX_HEIGHT.get();
        if (configured > GREENHOUSE_VOLUME_CEILING && !warnedCeiling) {
            warnedCeiling = true;
            LogUtils.getLogger().warn("crop_climates: greenhouseMaxRadius and greenhouseMaxHeight allow {} cells; "
                    + "greenhouses are capped at {}", configured, GREENHOUSE_VOLUME_CEILING);
        }
        return (int) Math.min(GREENHOUSE_VOLUME_CEILING, configured);
    }

    private CropClimatesConfig() {
    }
}
