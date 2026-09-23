package com.worldtraveler.cropclimates.stress;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One kind of planting for the builders. Every type fills one floor cell
 * (the soil layer at {@code y}) and what grows on it at {@code y + 1},
 * following what vanilla needs for it to actually grow: farmland next to
 * the water rows, cane on dirt beside water, spaced cacti, spaced saplings.
 * Every ninth row ({@code lz % 9 == 4}) is a water channel for all types.
 */
public enum PlantType {
    WHEAT, CARROTS, POTATOES, BEETROOTS, TORCHFLOWER, PITCHER,
    MELON_STEM, PUMPKIN_STEM, SWEET_BERRY, SUGAR_CANE, BAMBOO, CACTUS, NETHER_WART,
    OAK_SAPLING, SPRUCE_SAPLING, BIRCH_SAPLING, JUNGLE_SAPLING, ACACIA_SAPLING, DARK_OAK_SAPLING, CHERRY_SAPLING,
    MEGA_JUNGLE, MEGA_SPRUCE, EMPTY;

    static final BlockState FARMLAND = Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 7);
    static final BlockState WATER = Blocks.WATER.defaultBlockState();

    public static List<PlantType> parse(String csv) {
        List<PlantType> out = new ArrayList<>();
        for (String s : csv.split(",")) {
            if (!s.isBlank()) {
                out.add(valueOf(s.trim().toUpperCase(Locale.ROOT)));
            }
        }
        return out;
    }

    public static boolean waterRow(int lz) {
        return Math.floorMod(lz, 9) == 4;
    }

    /**
     * Places this type at local cell {@code (lx, lz)} whose soil block is at {@code (x, y, z)}.
     *
     * @param age -1 for a random age below the maximum, else that age (clamped below the maximum)
     */
    public void place(ServerLevel level, int x, int y, int z, int lx, int lz, int age, RandomSource random) {
        BlockPos soil = new BlockPos(x, y, z);
        BlockPos plant = soil.above();
        if (waterRow(lz) && this != EMPTY) {
            Builders.set(level, soil, WATER);
            return;
        }
        switch (this) {
            case WHEAT -> crop(level, soil, plant, Blocks.WHEAT, age, random);
            case CARROTS -> crop(level, soil, plant, Blocks.CARROTS, age, random);
            case POTATOES -> crop(level, soil, plant, Blocks.POTATOES, age, random);
            case BEETROOTS -> crop(level, soil, plant, Blocks.BEETROOTS, age, random);
            case TORCHFLOWER -> crop(level, soil, plant, Blocks.TORCHFLOWER_CROP, age, random);
            case PITCHER -> {
                Builders.set(level, soil, FARMLAND);
                // Ages 3+ are two blocks tall; start below that so one block is enough.
                Builders.set(level, plant, withAge(Blocks.PITCHER_CROP.defaultBlockState(), age, 2, random));
            }
            case MELON_STEM, PUMPKIN_STEM -> {
                Builders.set(level, soil, FARMLAND);
                if (Math.floorMod(lx, 2) == 0) {
                    Block stem = this == MELON_STEM ? Blocks.MELON_STEM : Blocks.PUMPKIN_STEM;
                    Builders.set(level, plant, withAge(stem.defaultBlockState(), age, 7, random));
                }
            }
            case SWEET_BERRY -> {
                Builders.set(level, soil, Blocks.GRASS_BLOCK.defaultBlockState());
                // Age 3 is ripe and stops growing.
                Builders.set(level, plant, withAge(Blocks.SWEET_BERRY_BUSH.defaultBlockState(), age, 3, random));
            }
            case SUGAR_CANE -> {
                int row = Math.floorMod(lz, 9);
                if (row == 3 || row == 5) {
                    Builders.set(level, soil, Blocks.SAND.defaultBlockState());
                    Builders.set(level, plant, withAge(Blocks.SUGAR_CANE.defaultBlockState(), age, 16, random));
                } else {
                    Builders.set(level, soil, Blocks.GRASS_BLOCK.defaultBlockState());
                }
            }
            case BAMBOO -> {
                Builders.set(level, soil, Blocks.GRASS_BLOCK.defaultBlockState());
                if (Math.floorMod(lx + lz, 2) == 0) {
                    Builders.set(level, plant, Blocks.BAMBOO.defaultBlockState());
                }
            }
            case CACTUS -> {
                Builders.set(level, soil, Blocks.SAND.defaultBlockState());
                if (Math.floorMod(lx, 2) == 0 && Math.floorMod(lz, 2) == 0) {
                    Builders.set(level, plant, withAge(Blocks.CACTUS.defaultBlockState(), age, 16, random));
                }
            }
            case NETHER_WART -> {
                Builders.set(level, soil, Blocks.SOUL_SAND.defaultBlockState());
                Builders.set(level, plant, withAge(Blocks.NETHER_WART.defaultBlockState(), age, 3, random));
            }
            case OAK_SAPLING, SPRUCE_SAPLING, BIRCH_SAPLING, JUNGLE_SAPLING, ACACIA_SAPLING, DARK_OAK_SAPLING, CHERRY_SAPLING -> {
                Builders.set(level, soil, Blocks.GRASS_BLOCK.defaultBlockState());
                if (Math.floorMod(lx, 3) == 1 && Math.floorMod(lz, 3) == 1) {
                    Builders.set(level, plant, sapling(this));
                }
            }
            case MEGA_JUNGLE, MEGA_SPRUCE -> {
                Builders.set(level, soil, Blocks.GRASS_BLOCK.defaultBlockState());
                int mx = Math.floorMod(lx, 7), mz = Math.floorMod(lz, 7);
                if ((mx == 2 || mx == 3) && (mz == 2 || mz == 3)) {
                    Builders.set(level, plant, this == MEGA_JUNGLE
                            ? Blocks.JUNGLE_SAPLING.defaultBlockState()
                            : Blocks.SPRUCE_SAPLING.defaultBlockState());
                }
            }
            case EMPTY -> Builders.set(level, soil, Blocks.GRASS_BLOCK.defaultBlockState());
        }
    }

    private static void crop(ServerLevel level, BlockPos soil, BlockPos plant, Block crop, int age, RandomSource random) {
        Builders.set(level, soil, FARMLAND);
        BlockState state = crop.defaultBlockState();
        IntegerProperty prop = ageProperty(state);
        int max = prop == null ? 0 : prop.getPossibleValues().stream().mapToInt(Integer::intValue).max().orElse(0);
        Builders.set(level, plant, withAge(state, age, max, random));
    }

    private static BlockState sapling(PlantType type) {
        return switch (type) {
            case SPRUCE_SAPLING -> Blocks.SPRUCE_SAPLING.defaultBlockState();
            case BIRCH_SAPLING -> Blocks.BIRCH_SAPLING.defaultBlockState();
            case JUNGLE_SAPLING -> Blocks.JUNGLE_SAPLING.defaultBlockState();
            case ACACIA_SAPLING -> Blocks.ACACIA_SAPLING.defaultBlockState();
            case DARK_OAK_SAPLING -> Blocks.DARK_OAK_SAPLING.defaultBlockState();
            case CHERRY_SAPLING -> Blocks.CHERRY_SAPLING.defaultBlockState();
            default -> Blocks.OAK_SAPLING.defaultBlockState();
        };
    }

    /** {@code state} with an age below {@code maxExclusive}: random when {@code age < 0}. */
    static BlockState withAge(BlockState state, int age, int maxExclusive, RandomSource random) {
        IntegerProperty prop = ageProperty(state);
        if (prop == null || maxExclusive <= 0) {
            return state;
        }
        int value = age < 0 ? random.nextInt(maxExclusive) : Math.min(age, maxExclusive - 1);
        value = Math.max(value, prop.getPossibleValues().stream().mapToInt(Integer::intValue).min().orElse(0));
        return prop.getPossibleValues().contains(value) ? state.setValue(prop, value) : state;
    }

    static IntegerProperty ageProperty(BlockState state) {
        for (Property<?> p : state.getProperties()) {
            if (p instanceof IntegerProperty ip && (p == BlockStateProperties.AGE_1 || "age".equals(p.getName()))) {
                return ip;
            }
        }
        return null;
    }
}
