package com.worldtraveler.cropclimates.climate;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.tags.TagKey;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.biome.Biome;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Humidity axis. Live read of each biome's own {@code downfall} - the KubeJS
 * system baked this into a 207-entry table only because a live read risked a
 * Rhino method rename; that risk does not exist in Java. See
 * {@code design_reference_kubejs_implementation.md} section 2 and the crop
 * climate Java port plan's "approved change" for this class.
 *
 * <p>An override layer from {@code data/crop_climates/climate/biome_moisture.json}
 * (folder {@code climate}, any number of contributing files) lets a datapack
 * pin specific biomes or tags to a fixed value - shipped with the End forced
 * to 0.0, matching the old table.
 */
public final class BiomeMoisture extends SimpleJsonResourceReloadListener {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();

    private static volatile Map<ResourceLocation, Double> BIOME_OVERRIDES = Map.of();
    private static volatile Map<TagKey<Biome>, Double> TAG_OVERRIDES = Map.of();
    private static final Map<ResourceKey<Biome>, Double> CACHE = new IdentityHashMap<>();

    public BiomeMoisture() {
        super(GSON, "climate");
    }

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

        double value = resolve(biomeHolder, defaultBiomeMoisture);

        if (key != null) {
            synchronized (CACHE) {
                CACHE.put(key, value);
            }
        }
        return value;
    }

    private static double resolve(Holder<Biome> biomeHolder, double defaultBiomeMoisture) {
        ResourceKey<Biome> key = biomeHolder.unwrapKey().orElse(null);
        if (key != null) {
            Double byId = BIOME_OVERRIDES.get(key.location());
            if (byId != null) {
                return byId;
            }
        }
        for (Map.Entry<TagKey<Biome>, Double> entry : TAG_OVERRIDES.entrySet()) {
            if (biomeHolder.is(entry.getKey())) {
                return entry.getValue();
            }
        }
        if (!biomeHolder.isBound()) {
            return defaultBiomeMoisture;
        }
        double downfall = biomeHolder.value().getModifiedClimateSettings().downfall();
        return Math.max(0.0, Math.min(1.0, downfall));
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<ResourceLocation, Double> biomeOverrides = new HashMap<>();
        Map<TagKey<Biome>, Double> tagOverrides = new HashMap<>();

        for (Map.Entry<ResourceLocation, JsonElement> fileEntry : resources.entrySet()) {
            if (!fileEntry.getValue().isJsonObject()) {
                LOGGER.error("crop_climates: skipping malformed climate override file {} - not a JSON object", fileEntry.getKey());
                continue;
            }
            JsonObject file = fileEntry.getValue().getAsJsonObject();

            readSection(file, "biomes", fileEntry.getKey(), (id, value) -> {
                try {
                    biomeOverrides.put(ResourceLocation.parse(id), value);
                } catch (RuntimeException ex) {
                    LOGGER.warn("crop_climates: skipping unresolvable biome id '{}' in {} - {}", id, fileEntry.getKey(), ex.toString());
                }
            });

            readSection(file, "tags", fileEntry.getKey(), (id, value) -> {
                try {
                    String path = id.startsWith("#") ? id.substring(1) : id;
                    tagOverrides.put(TagKey.create(Registries.BIOME, ResourceLocation.parse(path)), value);
                } catch (RuntimeException ex) {
                    LOGGER.warn("crop_climates: skipping unresolvable biome tag '{}' in {} - {}", id, fileEntry.getKey(), ex.toString());
                }
            });
        }

        BIOME_OVERRIDES = Map.copyOf(biomeOverrides);
        TAG_OVERRIDES = Map.copyOf(tagOverrides);
        synchronized (CACHE) {
            CACHE.clear();
        }

        LOGGER.info("crop_climates: loaded {} biome moisture overrides, {} tag overrides",
                biomeOverrides.size(), tagOverrides.size());
    }

    private interface EntryConsumer {
        void accept(String id, double value);
    }

    private static void readSection(JsonObject file, String section, ResourceLocation source, EntryConsumer consumer) {
        JsonObject obj = file.getAsJsonObject(section);
        if (obj == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
            try {
                consumer.accept(e.getKey(), e.getValue().getAsDouble());
            } catch (RuntimeException ex) {
                LOGGER.warn("crop_climates: skipping malformed entry '{}' in {} - {}", e.getKey(), source, ex.toString());
            }
        }
    }
}
