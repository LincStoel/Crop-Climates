package com.worldtraveler.cropclimates.climate;

/**
 * One plant's ideal temperature and humidity bands, translated from a
 * {@code crop_climate/*.json} datapack entry. See
 * {@code design_reference_kubejs_implementation.md} section 4.
 */
public record ClimateBand(
        double tempLo,
        double tempHi,
        double moistLo,
        double moistHi,
        boolean tree,
        boolean aquatic,
        Hook hook
) {
    public enum Hook {
        CROP_GROW_EVENT,
        RANDOM_TICK
    }
}
