package com.worldtraveler.cropclimates.climate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins {@link EnclosureHumidity}'s weighting/normalization/clamping math.
 */
class EnclosureHumidityTest {

    private static final double DELTA = 1e-9;

    @Test
    void weightsMatchFixedRatios() {
        assertEquals(1, EnclosureHumidity.weightOf(EnclosureHumidity.Effect.WATER));
        assertEquals(1, EnclosureHumidity.weightOf(EnclosureHumidity.Effect.HUMIDIFIER));
        assertEquals(-1, EnclosureHumidity.weightOf(EnclosureHumidity.Effect.DESICCANT));
        assertEquals(-3, EnclosureHumidity.weightOf(EnclosureHumidity.Effect.LAVA));
        assertEquals(0, EnclosureHumidity.weightOf(EnclosureHumidity.Effect.NONE));
    }

    @Test
    void zeroNetWeightLeavesBiomeHumidityUnchanged() {
        assertEquals(0.42, EnclosureHumidity.compute(0.42, 0, 20, 3.0), DELTA);
    }

    @Test
    void positiveWeightRaisesHumidity() {
        // k=3, two water cells (netWeight=2), room of 20 -> density = 6/20 = 0.3
        assertEquals(0.5, EnclosureHumidity.compute(0.2, 2, 20, 3.0), DELTA);
    }

    @Test
    void negativeWeightLowersHumidity() {
        // k=3, one lava cell (netWeight=-3), room of 12 -> density = -9/12 = -0.75
        assertEquals(0.05, EnclosureHumidity.compute(0.8, -3, 12, 3.0), DELTA);
    }

    @Test
    void sameWeightMattersLessInALargerRoom() {
        double small = EnclosureHumidity.compute(0.3, 1, 12, 3.0);
        double large = EnclosureHumidity.compute(0.3, 1, 768, 3.0);
        assertEquals(0.3 + 3.0 / 12, small, DELTA);
        assertEquals(0.3 + 3.0 / 768, large, DELTA);
    }

    @Test
    void clampsAtUpperBound() {
        assertEquals(1.0, EnclosureHumidity.compute(0.9, 10, 5, 3.0), DELTA);
    }

    @Test
    void clampsAtLowerBound() {
        assertEquals(0.0, EnclosureHumidity.compute(0.1, -10, 5, 3.0), DELTA);
    }

    @Test
    void zeroRoomSizeDoesNotDivideByZero() {
        assertEquals(0.5, EnclosureHumidity.compute(0.5, 4, 0, 3.0), DELTA);
    }
}
