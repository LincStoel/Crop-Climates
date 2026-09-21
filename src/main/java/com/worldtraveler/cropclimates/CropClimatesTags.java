package com.worldtraveler.cropclimates;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

public final class CropClimatesTags {

    /** Seeds the Soil Tester recipe accepts - {@code #c:seeds} plus the vanilla seeds, non-required. */
    public static final TagKey<Item> SEEDS = item("seeds");

    /** Blocks that dry out an enclosed space's humidity - seeded with dry sponge. */
    public static final TagKey<Block> DESICCANT = block("desiccant");
    /** Blocks that raise an enclosed space's humidity - seeded with wet sponge. */
    public static final TagKey<Block> HUMIDIFIER = block("humidifier");

    private static TagKey<Item> item(String path) {
        return TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(CropClimates.MOD_ID, path));
    }

    private static TagKey<Block> block(String path) {
        return TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(CropClimates.MOD_ID, path));
    }

    private CropClimatesTags() {
    }
}
