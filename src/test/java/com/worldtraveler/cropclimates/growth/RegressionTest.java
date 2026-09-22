package com.worldtraveler.cropclimates.growth;

import com.worldtraveler.cropclimates.climate.ClimateBand;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RegressionTest {

    private static final ClimateBand BAND = new ClimateBand(50, 80, 0.3, 0.7, false, false,
            ClimateBand.Hook.CROP_GROW_EVENT, true);

    private static GrowthGovernor.GrowthReading reading(double tempF, double humidity, double fitT, double fitM) {
        GrowthGovernor.Conditions c = new GrowthGovernor.Conditions(tempF, false, humidity, humidity, false,
                GrowthGovernor.HumidityWaiver.NONE, null, true);
        return new GrowthGovernor.GrowthReading(0.1, fitT, fitM, c);
    }

    @Test
    void comfortablePlantDoesNotWilt() {
        assertNull(Regression.cause(BAND, reading(65, 0.5, 0.9, 1.0), 0.25));
    }

    @Test
    void temperatureCausesPickColdOrHot() {
        assertEquals(Regression.Cause.COLD, Regression.cause(BAND, reading(10, 0.5, 0.1, 1.0), 0.25));
        assertEquals(Regression.Cause.HOT, Regression.cause(BAND, reading(120, 0.5, 0.1, 1.0), 0.25));
    }

    @Test
    void humidityCausesPickDryOrWet() {
        assertEquals(Regression.Cause.DRY, Regression.cause(BAND, reading(65, 0.0, 0.9, 0.1), 0.25));
        assertEquals(Regression.Cause.WET, Regression.cause(BAND, reading(65, 1.0, 0.9, 0.1), 0.25));
    }

    @Test
    void worseAxisWinsWhenBothAreBad() {
        assertEquals(Regression.Cause.DRY, Regression.cause(BAND, reading(10, 0.0, 0.2, 0.05), 0.25));
        assertEquals(Regression.Cause.COLD, Regression.cause(BAND, reading(10, 0.0, 0.05, 0.2), 0.25));
    }
}
