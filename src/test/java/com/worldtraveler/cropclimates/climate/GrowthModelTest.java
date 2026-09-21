package com.worldtraveler.cropclimates.climate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link GrowthModel} against the worked examples in
 * {@code design_reference_kubejs_implementation.md} - the growth math must
 * produce byte-for-byte the same multipliers the KubeJS system did.
 */
class GrowthModelTest {

    private static final double FLOOR = 0.01;
    private static final double MAX = 1.25;
    private static final double CURVE = 1.0;
    private static final double TEMP_TOL = 15.0;
    private static final double MOIST_TOL = 0.15;
    private static final double SKY_PENALTY = 0.85;

    private static final double DELTA = 1e-4;

    // Wheat: 40-90 F, 0.25-0.85 humidity
    private static final double WHEAT_T_LO = 40, WHEAT_T_HI = 90;
    private static final double WHEAT_M_LO = 0.25, WHEAT_M_HI = 0.85;

    // Cactus: 56-126 F, 0.00-0.30 humidity
    private static final double CACTUS_T_LO = 56, CACTUS_T_HI = 126;
    private static final double CACTUS_M_LO = 0.0, CACTUS_M_HI = 0.30;

    private static double multiplier(double tempF, double moisture, double tLo, double tHi,
                                      double mLo, double mHi, double give, boolean humidityWaived,
                                      boolean canSeeSky) {
        double fitM = GrowthModel.fitEdge(moisture, mLo, mHi, MOIST_TOL * give);
        if (humidityWaived) {
            fitM = 1.0;
        }
        double fitT = GrowthModel.fit(tempF, tLo, tHi, TEMP_TOL * give);
        double total = GrowthModel.total(fitT, fitM, FLOOR, MAX, CURVE);
        if (!canSeeSky) {
            total *= SKY_PENALTY;
        }
        return total;
    }

    @Test
    void wheatPlains() {
        assertEquals(1.24, multiplier(70, 0.40, WHEAT_T_LO, WHEAT_T_HI, WHEAT_M_LO, WHEAT_M_HI,
                1.0, false, true), 0.005);
    }

    @Test
    void wheatPlainsRoofed() {
        assertEquals(1.05, multiplier(70, 0.40, WHEAT_T_LO, WHEAT_T_HI, WHEAT_M_LO, WHEAT_M_HI,
                1.0, false, false), 0.005);
    }

    @Test
    void wheatDesert() {
        assertEquals(0.10, multiplier(115, 0.0, WHEAT_T_LO, WHEAT_T_HI, WHEAT_M_LO, WHEAT_M_HI,
                1.0, false, true), 0.005);
    }

    @Test
    void wheatDesertHumidityWaived() {
        assertEquals(0.29, multiplier(115, 0.0, WHEAT_T_LO, WHEAT_T_HI, WHEAT_M_LO, WHEAT_M_HI,
                1.0, true, true), 0.005);
    }

    @Test
    void wheatDesertHearth() {
        assertEquals(0.38, multiplier(75, 0.0, WHEAT_T_LO, WHEAT_T_HI, WHEAT_M_LO, WHEAT_M_HI,
                1.0, false, true), 0.005);
    }

    @Test
    void wheatDesertHearthAndHumidityWaived() {
        assertEquals(1.19, multiplier(75, 0.0, WHEAT_T_LO, WHEAT_T_HI, WHEAT_M_LO, WHEAT_M_HI,
                1.0, true, true), 0.005);
    }

    @Test
    void cactusDesert() {
        assertEquals(1.09, multiplier(115, 0.0, CACTUS_T_LO, CACTUS_T_HI, CACTUS_M_LO, CACTUS_M_HI,
                1.0, false, true), 0.005);
    }

    @Test
    void cactusSwamp() {
        assertEquals(0.09, multiplier(80, 0.90, CACTUS_T_LO, CACTUS_T_HI, CACTUS_M_LO, CACTUS_M_HI,
                1.0, false, true), 0.005);
    }

    @Test
    void cactusSwampUnwaivedStaysPunished() {
        // With no humidity waiver in play, a cactus drowning in a swamp keeps
        // paying the full penalty for being above its band.
        assertEquals(0.09, multiplier(80, 0.90, CACTUS_T_LO, CACTUS_T_HI, CACTUS_M_LO, CACTUS_M_HI,
                1.0, false, true), 0.005);
    }

    @Test
    void cactusSwampWaivedRecovers() {
        // Unlike the old rich-soil rescue, the surviving waivers (submerged,
        // enclosed) are two-sided: they also fix a crop that is too WET.
        double unwaived = multiplier(80, 0.90, CACTUS_T_LO, CACTUS_T_HI, CACTUS_M_LO, CACTUS_M_HI,
                1.0, false, true);
        double waived = multiplier(80, 0.90, CACTUS_T_LO, CACTUS_T_HI, CACTUS_M_LO, CACTUS_M_HI,
                1.0, true, true);
        assertTrue(waived > unwaived, "a humidity waiver must rescue a too-wet crop, not just a too-dry one");
    }

    @Test
    void waiverOnlyTouchesHumidityAxis() {
        // A waived crop in a desert must still score its temperature
        // normally - the waivers touch fitM only, never fitT.
        double waivedInDesert = multiplier(115, 0.0, WHEAT_T_LO, WHEAT_T_HI, WHEAT_M_LO, WHEAT_M_HI,
                1.0, true, true);
        double perfectHumidityInDesert = multiplier(115, (WHEAT_M_LO + WHEAT_M_HI) / 2, WHEAT_T_LO, WHEAT_T_HI,
                WHEAT_M_LO, WHEAT_M_HI, 1.0, false, true);
        assertEquals(perfectHumidityInDesert, waivedInDesert, DELTA);
    }

    @Test
    void deadCentreIsExactlyGrowthMax() {
        double mid = (WHEAT_T_LO + WHEAT_T_HI) / 2.0;
        double fitT = GrowthModel.fit(mid, WHEAT_T_LO, WHEAT_T_HI, TEMP_TOL);
        double fitM = GrowthModel.fitEdge(WHEAT_M_LO + 0.1, WHEAT_M_LO, WHEAT_M_HI, MOIST_TOL);
        assertEquals(1.0, fitT, DELTA);
        assertEquals(1.0, fitM, DELTA);
        assertEquals(MAX, GrowthModel.total(fitT, fitM, FLOOR, MAX, CURVE), DELTA);
    }

    @Test
    void temperatureBandEdgeFitIsPointSeventyTwo() {
        assertEquals(0.72, GrowthModel.fit(WHEAT_T_LO, WHEAT_T_LO, WHEAT_T_HI, TEMP_TOL), DELTA);
        assertEquals(0.72, GrowthModel.fit(WHEAT_T_HI, WHEAT_T_LO, WHEAT_T_HI, TEMP_TOL), DELTA);
    }

    @Test
    void humidityFitIsFlatAcrossTheWholeBand() {
        assertEquals(1.0, GrowthModel.fitEdge(WHEAT_M_LO, WHEAT_M_LO, WHEAT_M_HI, MOIST_TOL), DELTA);
        assertEquals(1.0, GrowthModel.fitEdge(WHEAT_M_HI, WHEAT_M_LO, WHEAT_M_HI, MOIST_TOL), DELTA);
        assertEquals(1.0, GrowthModel.fitEdge((WHEAT_M_LO + WHEAT_M_HI) / 2, WHEAT_M_LO, WHEAT_M_HI, MOIST_TOL), DELTA);
    }

    @Test
    void treeForgivenessWidensBothTolerances() {
        double beyond = WHEAT_T_HI + 10;
        double withoutForgiveness = GrowthModel.fit(beyond, WHEAT_T_LO, WHEAT_T_HI, TEMP_TOL);
        double withForgiveness = GrowthModel.fit(beyond, WHEAT_T_LO, WHEAT_T_HI, TEMP_TOL * 1.8);
        assertTrue(withForgiveness > withoutForgiveness,
                "wider tolerance must soften the falloff outside the band");
    }
}
