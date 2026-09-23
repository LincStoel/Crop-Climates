package com.worldtraveler.cropclimates.climate;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.tags.TagKey;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.biome.Biome;
import org.slf4j.Logger;

import java.io.Reader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Humidity axis: each biome's own {@code downfall}, read live, with an
 * override layer from {@code data/<any>/climate/*.json}:
 *
 * <pre>{@code
 * { "overrides": [
 *   { "biomes": ["#minecraft:is_end"], "moisture": 0.0 },
 *   { "biomes": ["minecraft:mushroom_fields", "#c:is_swamp"], "moisture": 0.9, "priority": 10 }
 * ]}
 * }</pre>
 *
 * Precedence between overlapping entries lives in {@link MoistureOverrides}.
 * Every datapack's copy of a file is read, lowest pack first. Resolved values
 * are cached per biome and dropped on reload and on tag updates.
 */
public final class BiomeMoisture extends SimplePreparableReloadListener<List<MoistureOverrides.Entry>> {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final FileToIdConverter FILES = FileToIdConverter.json("climate");

    private record RawEntry(List<String> biomes, double moisture, int priority) {
        static final Codec<RawEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.listOf().fieldOf("biomes").forGetter(RawEntry::biomes),
                Codec.DOUBLE.validate(v -> v < 0.0 || v > 1.0
                        ? DataResult.error(() -> "moisture must lie within 0-1, got " + v)
                        : DataResult.success(v)).fieldOf("moisture").forGetter(RawEntry::moisture),
                Codec.INT.optionalFieldOf("priority", 0).forGetter(RawEntry::priority)
        ).apply(instance, RawEntry::new));
    }

    private static final Codec<List<RawEntry>> FILE_CODEC =
            RawEntry.CODEC.listOf().fieldOf("overrides").codec();

    private static volatile MoistureOverrides OVERRIDES = new MoistureOverrides(List.of());
    private static final Map<ResourceKey<Biome>, Double> CACHE = new IdentityHashMap<>();
    /** Biome id -> description of the tie, for {@code /cropclimates audit}. */
    private static final Map<ResourceLocation, String> CONFLICTS = new TreeMap<>();

    /** Moisture (0-1) for the biome at this holder, honouring overrides. */
    public static double moistureOf(Holder<Biome> biomeHolder, double defaultBiomeMoisture) {
        ResourceKey<Biome> key = biomeHolder.unwrapKey().orElse(null);
        if (key != null) {
            synchronized (CACHE) {
                Double cached = CACHE.get(key);
                if (cached != null) {
                    return cached;
                }
            }
        }

        double value = resolve(biomeHolder, key, defaultBiomeMoisture);

        if (key != null) {
            synchronized (CACHE) {
                CACHE.put(key, value);
            }
        }
        return value;
    }

    private static double resolve(Holder<Biome> biomeHolder, ResourceKey<Biome> key, double defaultBiomeMoisture) {
        ResourceLocation id = key != null ? key.location() : null;
        MoistureOverrides.Result result = OVERRIDES.resolve(id,
                tag -> biomeHolder.is(TagKey.create(Registries.BIOME, tag)));
        if (result != null) {
            if (result.conflicted() && id != null) {
                String losers = result.conflictsWith().stream().map(MoistureOverrides.Entry::source)
                        .collect(Collectors.joining(", "));
                String description = "uses " + result.moisture() + " from " + result.winner().source()
                        + ", tied with " + losers;
                boolean fresh;
                synchronized (CONFLICTS) {
                    fresh = CONFLICTS.put(id, description) == null;
                }
                if (fresh) {
                    LOGGER.warn("crop_climates: biome {} matches equal-priority moisture overrides - {}", id, description);
                }
            }
            return result.moisture();
        }
        if (!biomeHolder.isBound()) {
            return defaultBiomeMoisture;
        }
        double downfall = biomeHolder.value().getModifiedClimateSettings().downfall();
        return Math.max(0.0, Math.min(1.0, downfall));
    }

    /** Equal-priority ties found so far this reload, biome id -> description. */
    public static Map<ResourceLocation, String> conflicts() {
        synchronized (CONFLICTS) {
            return Map.copyOf(CONFLICTS);
        }
    }

    /** Tag membership may have changed - forget every resolved value. */
    public static void invalidate() {
        synchronized (CACHE) {
            CACHE.clear();
        }
        synchronized (CONFLICTS) {
            CONFLICTS.clear();
        }
    }

    @Override
    protected List<MoistureOverrides.Entry> prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        List<MoistureOverrides.Entry> entries = new ArrayList<>();
        int order = 0;
        for (Map.Entry<ResourceLocation, List<Resource>> stack : FILES.listMatchingResourceStacks(resourceManager).entrySet()) {
            ResourceLocation fileId = FILES.fileToId(stack.getKey());
            for (Resource resource : stack.getValue()) {
                String source = fileId + " (" + resource.sourcePackId() + ")";
                JsonElement json;
                try (Reader reader = resource.openAsReader()) {
                    json = JsonParser.parseReader(reader);
                } catch (Exception ex) {
                    LOGGER.error("crop_climates: could not read climate file {} - {}", source, ex.toString());
                    continue;
                }
                List<RawEntry> raw = FILE_CODEC.parse(JsonOps.INSTANCE, json)
                        .resultOrPartial(error -> LOGGER.error("crop_climates: skipping climate file {} - {}", source, error))
                        .orElse(List.of());
                for (RawEntry entry : raw) {
                    Set<ResourceLocation> biomes = new HashSet<>();
                    Set<ResourceLocation> tags = new HashSet<>();
                    for (String name : entry.biomes()) {
                        boolean tag = name.startsWith("#");
                        ResourceLocation id = ResourceLocation.tryParse(tag ? name.substring(1) : name);
                        if (id == null) {
                            LOGGER.warn("crop_climates: skipping invalid biome '{}' in {}", name, source);
                            continue;
                        }
                        (tag ? tags : biomes).add(id);
                    }
                    entries.add(new MoistureOverrides.Entry(biomes, tags, entry.moisture(), entry.priority(), order++, source));
                }
            }
        }
        return entries;
    }

    @Override
    protected void apply(List<MoistureOverrides.Entry> entries, ResourceManager resourceManager, ProfilerFiller profiler) {
        OVERRIDES = new MoistureOverrides(entries);
        invalidate();
        LOGGER.info("crop_climates: loaded {} biome moisture overrides", entries.size());
    }
}
