package com.worldtraveler.cropclimates.climate;

/**
 * Pure aggregation math for enclosed humidity - no Minecraft world access, so
 * it is unit-testable without a level, exactly as {@link GrowthModel} is
 * pure. Classification of individual cells (fluid/tag lookups) is world-backed
 * and lives in {@code EnclosureSampler}; this class only turns an already
 * summed weight into a humidity delta.
 */
public final class EnclosureHumidity {

    private EnclosureHumidity() {
    }

    public enum Effect { NONE, WATER, LAVA, DESICCANT, HUMIDIFIER }

    /** Fixed ratios: water/humidifier +1, desiccant -1, lava -3 (3x water's magnitude). */
    public static int weightOf(Effect effect) {
        return switch (effect) {
            case WATER, HUMIDIFIER -> 1;
            case DESICCANT -> -1;
            case LAVA -> -3;
            case NONE -> 0;
        };
    }

    /**
     * {@code clamp(biomeHumidity + k * netWeight / roomSize, 0.0, 1.0)}. A
     * larger room dilutes the same net weight into a smaller shift.
     */
    public static double compute(double biomeHumidity, int netWeight, int roomSize, double k) {
        double density = roomSize > 0 ? k * netWeight / roomSize : 0.0;
        return Math.max(0.0, Math.min(1.0, biomeHumidity + density));
    }
}
