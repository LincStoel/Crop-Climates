package com.worldtraveler.cropclimates;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Tuning constants. A SERVER config, so it lives per world
 * ({@code <world>/serverconfig/crop_climates-server.toml}) and is only ever
 * read on the logical server.
 */
public final class CropClimatesConfig {

    public enum Units { F, C }

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
    public static final ModConfigSpec.IntValue GREENHOUSE_MAX_VOLUME;
    public static final ModConfigSpec.IntValue GREENHOUSE_MIN_VOLUME;
    public static final ModConfigSpec.IntValue GREENHOUSE_SKY_SCAN;
    public static final ModConfigSpec.IntValue GREENHOUSE_CELLS_PER_TICK;
    public static final ModConfigSpec.IntValue GREENHOUSE_RESCAN_INTERVAL;
    public static final ModConfigSpec.IntValue GREENHOUSE_UNSEALED_RETRY_INTERVAL;
    public static final ModConfigSpec.IntValue GREENHOUSE_CHANGE_DELAY;
    public static final ModConfigSpec.DoubleValue HUMIDITY_BLOCK_MULTIPLIER;

    public static final ModConfigSpec.DoubleValue GREENHOUSE_DRIP_HUMIDITY;
    public static final ModConfigSpec.DoubleValue GREENHOUSE_DRIP_CHANCE;
    public static final ModConfigSpec.DoubleValue GREENHOUSE_DUST_HUMIDITY;
    public static final ModConfigSpec.DoubleValue GREENHOUSE_DUST_CHANCE;

    public static final ModConfigSpec.BooleanValue AQUATIC_USES_WATER_TEMPERATURE;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("growth");
        GROWTH_FLOOR = builder
                .comment("Slowest a plant can ever grow (multiplier).")
                .defineInRange("growthFloor", 0.01, 0.0, 10.0);
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
                .defineInRange("moistTolerance", 0.15, 0.001, 1.0);
        TREE_FORGIVENESS = builder
                .comment("Multiplies both tolerances above, saplings only.")
                .defineInRange("treeForgiveness", 1.8, 1.0, 100.0);
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
                .defineInRange("rainHumidityShift", 0.25, 0.0, 1.0);
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
                .comment("Cached temperature entries allowed before the whole cache is dropped.")
                .defineInRange("tempCacheCap", 4000, 1, Integer.MAX_VALUE);
        TEMP_READS_PER_TICK = builder
                .comment("Fresh Cold Sweat temperature reads allowed per game tick; over budget uses a stale reading.")
                .defineInRange("tempReadsPerTick", 12, 1, Integer.MAX_VALUE);
        ERROR_LIMIT = builder
                .comment("Logged failures before climate growth disables itself for the session and reverts to vanilla.")
                .defineInRange("errorLimit", 8, 1, Integer.MAX_VALUE);
        builder.pop();

        builder.push("greenhouse");
        GREENHOUSE_ENABLED = builder
                .comment("Whether a Hygrometer in a sealed room turns it into a greenhouse whose humidity replaces the biome's.")
                .define("greenhouseEnabled", true);
        GREENHOUSE_MAX_VOLUME = builder
                .comment("Largest greenhouse, in interior cells (air, water, crops - not walls). Bigger rooms read as too large.")
                .defineInRange("greenhouseMaxVolume", 4096, 1, Integer.MAX_VALUE);
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
                        "room size (walls included) and adding to biome humidity. Water/humidifier +1, " +
                        "desiccant -1, lava -3.")
                .defineInRange("humidityBlockMultiplier", 3.0, 0.0, 100.0);
        GREENHOUSE_DRIP_HUMIDITY = builder
                .comment("Humidity at/above which a greenhouse occasionally drips water from its ceiling.")
                .defineInRange("greenhouseDripHumidity", 0.9, 0.0, 1.0);
        GREENHOUSE_DRIP_CHANCE = builder
                .comment("Chance per governed random tick, per crop, of spawning one drip particle while above the drip humidity.")
                .defineInRange("greenhouseDripChance", 0.025, 0.0, 1.0);
        GREENHOUSE_DUST_HUMIDITY = builder
                .comment("Humidity at/below which a greenhouse infrequently puffs dust from its ceiling.")
                .defineInRange("greenhouseDustHumidity", 0.1, 0.0, 1.0);
        GREENHOUSE_DUST_CHANCE = builder
                .comment("Chance per governed random tick, per crop, of spawning one dust particle while below the dust humidity.")
                .defineInRange("greenhouseDustChance", 0.01, 0.0, 1.0);
        builder.pop();

        builder.push("aquatic");
        AQUATIC_USES_WATER_TEMPERATURE = builder
                .comment("Submerged aquatic crops score against Cold Sweat's water temperature instead of air.")
                .define("aquaticUsesWaterTemperature", true);
        builder.pop();

        SPEC = builder.build();
    }

    private CropClimatesConfig() {
    }
}
