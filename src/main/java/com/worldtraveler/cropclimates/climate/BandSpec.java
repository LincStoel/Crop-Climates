package com.worldtraveler.cropclimates.climate;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Optional;

/**
 * One {@code crop_climate/*.json} entry as written, before it is bound to
 * blocks. Parsed with {@link #CODEC}, so a malformed entry is rejected with a
 * precise message instead of a stray exception.
 *
 * <pre>{@code
 * "wheat": {
 *   "temperature": [50, 80],      // Fahrenheit, lo <= hi
 *   "humidity": [0.3, 0.7],       // 0-1, lo <= hi
 *   "hook": "CropGrowEvent",      // or "randomTick"; default CropGrowEvent
 *   "tree": false,                // saplings: wider tolerance, die back to a dead bush
 *   "aquatic": false,             // ignores humidity and uses water temperature while submerged
 *   "item": "minecraft:wheat_seeds", // tooltip item; default: the block's own item
 *   "regresses": true             // default depends on the block type
 * }
 * }</pre>
 */
public record BandSpec(
        Range temperature,
        Range humidity,
        ClimateBand.Hook hook,
        boolean tree,
        boolean aquatic,
        Optional<ResourceLocation> item,
        Optional<Boolean> regresses
) {

    public record Range(double lo, double hi) {
        public static final Codec<Range> CODEC = Codec.DOUBLE.listOf().comapFlatMap(
                list -> {
                    if (list.size() != 2) {
                        return DataResult.error(() -> "must be a two-element [lo, hi] array, got " + list);
                    }
                    if (list.get(0) > list.get(1)) {
                        return DataResult.error(() -> "lo " + list.get(0) + " is above hi " + list.get(1));
                    }
                    return DataResult.success(new Range(list.get(0), list.get(1)));
                },
                range -> List.of(range.lo(), range.hi()));
    }

    private static final Codec<Range> HUMIDITY_CODEC = Range.CODEC.validate(range ->
            range.lo() < 0.0 || range.hi() > 1.0
                    ? DataResult.error(() -> "humidity must lie within 0-1, got [" + range.lo() + ", " + range.hi() + "]")
                    : DataResult.success(range));

    private static final Codec<ClimateBand.Hook> HOOK_CODEC = Codec.STRING.comapFlatMap(
            name -> switch (name) {
                case "CropGrowEvent" -> DataResult.success(ClimateBand.Hook.CROP_GROW_EVENT);
                case "randomTick" -> DataResult.success(ClimateBand.Hook.RANDOM_TICK);
                default -> DataResult.error(() -> "unknown hook '" + name + "', expected CropGrowEvent or randomTick");
            },
            hook -> hook == ClimateBand.Hook.RANDOM_TICK ? "randomTick" : "CropGrowEvent");

    public static final Codec<BandSpec> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Range.CODEC.fieldOf("temperature").forGetter(BandSpec::temperature),
            HUMIDITY_CODEC.fieldOf("humidity").forGetter(BandSpec::humidity),
            HOOK_CODEC.optionalFieldOf("hook", ClimateBand.Hook.CROP_GROW_EVENT).forGetter(BandSpec::hook),
            Codec.BOOL.optionalFieldOf("tree", false).forGetter(BandSpec::tree),
            Codec.BOOL.optionalFieldOf("aquatic", false).forGetter(BandSpec::aquatic),
            ResourceLocation.CODEC.optionalFieldOf("item").forGetter(BandSpec::item),
            Codec.BOOL.optionalFieldOf("regresses").forGetter(BandSpec::regresses)
    ).apply(instance, BandSpec::new));

    public ClimateBand toBand(boolean regressesByDefault) {
        return new ClimateBand(temperature.lo(), temperature.hi(), humidity.lo(), humidity.hi(),
                tree, aquatic, hook, regresses.orElse(regressesByDefault));
    }
}
