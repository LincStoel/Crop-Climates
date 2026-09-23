package com.worldtraveler.cropclimates.climate;

/**
 * One plant's ideal temperature and humidity bands, resolved from a
 * {@code crop_climate/*.json} datapack entry (see {@link BandSpec}).
 * Temperatures are Fahrenheit; humidity is 0-1.
 *
 * @param regresses whether a plant far outside its bands slowly loses growth
 *                  stages - see {@code growth.Regression}
 */
public record ClimateBand(
        double tempLo,
        double tempHi,
        double moistLo,
        double moistHi,
        boolean tree,
        boolean aquatic,
        Hook hook,
        boolean regresses
) {
    public enum Hook {
        CROP_GROW_EVENT,
        RANDOM_TICK
    }
}
