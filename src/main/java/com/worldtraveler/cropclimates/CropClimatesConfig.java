package com.worldtraveler.cropclimates;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Tuning constants, COMMON config. Defaults are exactly the values baked into
 * the KubeJS system's {@code crop_growth_data.js} - see
 * {@code design_reference_kubejs_implementation.md} section 3.
 */
public final class CropClimatesConfig {

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.DoubleValue GROWTH_FLOOR;
    public static final ModConfigSpec.DoubleValue GROWTH_MAX;
    public static final ModConfigSpec.DoubleValue GROWTH_CURVE;

    public static final ModConfigSpec.DoubleValue TEMP_TOLERANCE;
    public static final ModConfigSpec.DoubleValue MOIST_TOLERANCE;
    public static final ModConfigSpec.DoubleValue TREE_FORGIVENESS;

    public static final ModConfigSpec.DoubleValue SKY_PENALTY;
    public static final ModConfigSpec.DoubleValue DEFAULT_BIOME_MOISTURE;

    public static final ModConfigSpec.IntValue TEMP_CACHE_TTL;
    public static final ModConfigSpec.IntValue TEMP_CACHE_CAP;
    public static final ModConfigSpec.IntValue TEMP_READS_PER_TICK;
    public static final ModConfigSpec.IntValue ERROR_LIMIT;

    public static final ModConfigSpec.BooleanValue GREENHOUSE_ENABLED;
    public static final ModConfigSpec.IntValue GREENHOUSE_MAX_VOLUME;
    public static final ModConfigSpec.IntValue GREENHOUSE_MIN_VOLUME;
    public static final ModConfigSpec.IntValue GREENHOUSE_MAX_RADIUS;
    public static final ModConfigSpec.IntValue GREENHOUSE_MAX_HEIGHT;
    public static final ModConfigSpec.IntValue GREENHOUSE_SKY_SCAN;
    public static final ModConfigSpec.IntValue GREENHOUSE_CACHE_TTL;
    public static final ModConfigSpec.IntValue GREENHOUSE_CACHE_CAP;
    public static final ModConfigSpec.IntValue GREENHOUSE_FILLS_PER_TICK;
    public static final ModConfigSpec.DoubleValue HUMIDITY_BLOCK_MULTIPLIER;

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
                .comment("Whether a sealed volume waives a crop's humidity requirement.")
                .define("greenhouseEnabled", true);
        GREENHOUSE_MAX_VOLUME = builder
                .comment("Cells a fill may visit before the space is judged too open. Counts wall blocks, not just air.")
                .defineInRange("greenhouseMaxVolume", 768, 1, Integer.MAX_VALUE);
        GREENHOUSE_MIN_VOLUME = builder
                .comment("Smallest sealed space that counts as a greenhouse. Stops a single slab over one crop.")
                .defineInRange("greenhouseMinVolume", 12, 1, Integer.MAX_VALUE);
        GREENHOUSE_MAX_RADIUS = builder
                .comment("Horizontal reach of the fill from the crop.")
                .defineInRange("greenhouseMaxRadius", 16, 1, Integer.MAX_VALUE);
        GREENHOUSE_MAX_HEIGHT = builder
                .comment("Vertical reach of the fill from the crop.")
                .defineInRange("greenhouseMaxHeight", 12, 1, Integer.MAX_VALUE);
        GREENHOUSE_SKY_SCAN = builder
                .comment("Blocks scanned upward per sky test. Matches the hearth's own value.")
                .defineInRange("greenhouseSkyScan", 64, 1, Integer.MAX_VALUE);
        GREENHOUSE_CACHE_TTL = builder
                .comment("Ticks an enclosure verdict stays valid. Also how long a broken wall keeps working.")
                .defineInRange("greenhouseCacheTtl", 600, 1, Integer.MAX_VALUE);
        GREENHOUSE_CACHE_CAP = builder
                .comment("Cached verdicts before the cache is dropped wholesale.")
                .defineInRange("greenhouseCacheCap", 4000, 1, Integer.MAX_VALUE);
        GREENHOUSE_FILLS_PER_TICK = builder
                .comment("Full flood fills per tick. Sky-escapes are not metered.")
                .defineInRange("greenhouseFillsPerTick", 2, 1, Integer.MAX_VALUE);
        HUMIDITY_BLOCK_MULTIPLIER = builder
                .comment("Scale applied to net water/lava/desiccant/humidifier weight before dividing by " +
                        "room size (walls included) and adding to biome humidity. Water/humidifier +1, " +
                        "desiccant -1, lava -3.")
                .defineInRange("humidity_block_multiplier", 3.0, 0.0, 100.0);
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
