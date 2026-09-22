package com.worldtraveler.cropclimates.climate;

import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BandSpecTest {

    private static DataResult<BandSpec> parse(String json) {
        return BandSpec.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json));
    }

    @Test
    void minimalEntryUsesDefaults() {
        BandSpec spec = parse("{\"temperature\":[50,80],\"humidity\":[0.3,0.7]}").getOrThrow();
        assertEquals(ClimateBand.Hook.CROP_GROW_EVENT, spec.hook());
        assertFalse(spec.tree());
        assertFalse(spec.aquatic());
        assertTrue(spec.item().isEmpty());
        assertTrue(spec.regresses().isEmpty());
        assertTrue(spec.toBand(true).regresses());
        assertFalse(spec.toBand(false).regresses());
    }

    @Test
    void explicitRegressesOverridesDefault() {
        BandSpec spec = parse("{\"temperature\":[50,80],\"humidity\":[0.3,0.7],\"regresses\":false}").getOrThrow();
        assertFalse(spec.toBand(true).regresses());
    }

    @Test
    void randomTickHookParses() {
        BandSpec spec = parse("{\"temperature\":[50,80],\"humidity\":[0,1],\"hook\":\"randomTick\",\"tree\":true}").getOrThrow();
        assertEquals(ClimateBand.Hook.RANDOM_TICK, spec.hook());
        assertTrue(spec.tree());
    }

    @Test
    void invertedRangeIsRejected() {
        assertTrue(parse("{\"temperature\":[80,50],\"humidity\":[0.3,0.7]}").isError());
    }

    @Test
    void humidityOutsideUnitRangeIsRejected() {
        assertTrue(parse("{\"temperature\":[50,80],\"humidity\":[0.3,1.5]}").isError());
    }

    @Test
    void wrongArityIsRejected() {
        assertTrue(parse("{\"temperature\":[50],\"humidity\":[0.3,0.7]}").isError());
    }

    @Test
    void unknownHookIsRejected() {
        assertTrue(parse("{\"temperature\":[50,80],\"humidity\":[0.3,0.7],\"hook\":\"tick\"}").isError());
    }
}
