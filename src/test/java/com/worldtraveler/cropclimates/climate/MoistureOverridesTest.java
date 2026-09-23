package com.worldtraveler.cropclimates.climate;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoistureOverridesTest {

    private static final ResourceLocation SWAMP = ResourceLocation.parse("minecraft:swamp");
    private static final ResourceLocation WET_TAG = ResourceLocation.parse("c:is_wet");
    private static final ResourceLocation HOT_TAG = ResourceLocation.parse("c:is_hot");

    private static MoistureOverrides.Entry biome(ResourceLocation id, double value, int priority, int order) {
        return new MoistureOverrides.Entry(Set.of(id), Set.of(), value, priority, order, "biome#" + order);
    }

    private static MoistureOverrides.Entry tag(ResourceLocation id, double value, int priority, int order) {
        return new MoistureOverrides.Entry(Set.of(), Set.of(id), value, priority, order, "tag#" + order);
    }

    private static MoistureOverrides.Result resolve(MoistureOverrides.Entry... entries) {
        return new MoistureOverrides(List.of(entries))
                .resolve(SWAMP, t -> t.equals(WET_TAG) || t.equals(HOT_TAG));
    }

    @Test
    void noMatchIsNull() {
        MoistureOverrides overrides = new MoistureOverrides(List.of(tag(WET_TAG, 0.9, 0, 0)));
        assertNull(overrides.resolve(SWAMP, t -> false));
    }

    @Test
    void exactIdBeatsHigherPriorityTag() {
        MoistureOverrides.Result result = resolve(tag(WET_TAG, 0.9, 100, 1), biome(SWAMP, 0.2, 0, 0));
        assertEquals(0.2, result.moisture());
        assertFalse(result.conflicted());
    }

    @Test
    void higherPriorityWinsAmongTags() {
        MoistureOverrides.Result result = resolve(tag(WET_TAG, 0.9, 5, 0), tag(HOT_TAG, 0.1, 1, 1));
        assertEquals(0.9, result.moisture());
        assertFalse(result.conflicted());
    }

    @Test
    void equalPriorityLaterWinsAndFlagsConflict() {
        MoistureOverrides.Result result = resolve(tag(WET_TAG, 0.9, 0, 0), tag(HOT_TAG, 0.1, 0, 1));
        assertEquals(0.1, result.moisture());
        assertTrue(result.conflicted());
        assertEquals("tag#0", result.conflictsWith().get(0).source());
    }

    @Test
    void equalPriorityAgreeingValuesAreNotAConflict() {
        MoistureOverrides.Result result = resolve(tag(WET_TAG, 0.5, 0, 0), tag(HOT_TAG, 0.5, 0, 1));
        assertFalse(result.conflicted());
    }
}
