package com.worldtraveler.cropclimates.climate;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Reload listener on folder {@code crop_climate}, replacing both copies of
 * {@code crop_climate_loader.js} (server and client - a single server-side map
 * plus the synced tooltip table covers both here, see {@code ClimateSyncPayload}).
 *
 * <p>Format is unchanged from the existing datapack: one file per owning
 * namespace, keys are block paths. The file's own name (the path component of
 * its resource location, after {@link SimpleJsonResourceReloadListener} strips
 * the {@code crop_climate/} directory and {@code .json} suffix) supplies the
 * namespace for un-namespaced keys - so {@code data/<any>/crop_climate/minecraft.json},
 * key {@code "wheat"}, resolves to block {@code minecraft:wheat} regardless of
 * which datapack namespace the file itself lives under.
 */
public final class ClimateBands extends SimpleJsonResourceReloadListener {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();

    private static volatile Map<Block, ClimateBand> BLOCK_BANDS = Map.of();
    private static volatile Set<Block> SAPLINGS = Set.of();
    private static volatile Set<Block> OWN_TICK = Set.of();
    private static volatile Map<Item, ClimateBand> ITEM_BANDS = Map.of();

    public ClimateBands() {
        super(GSON, "crop_climate");
    }

    public static ClimateBand bandFor(Block block) {
        return BLOCK_BANDS.get(block);
    }

    public static boolean isSapling(Block block) {
        return SAPLINGS.contains(block);
    }

    public static boolean isRandomTickGoverned(Block block) {
        return SAPLINGS.contains(block) || OWN_TICK.contains(block);
    }

    public static Map<Item, ClimateBand> itemBands() {
        return ITEM_BANDS;
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<Block, ClimateBand> blockBands = new HashMap<>();
        Set<Block> saplings = new HashSet<>();
        Set<Block> ownTick = new HashSet<>();
        Map<Item, String> itemToShortestBlockId = new HashMap<>();
        Map<Item, ClimateBand> itemBands = new HashMap<>();

        int plants = 0;
        int saplingCount = 0;
        int ownTickCount = 0;
        int skipped = 0;

        for (Map.Entry<ResourceLocation, JsonElement> fileEntry : resources.entrySet()) {
            String ownerNamespace = fileEntry.getKey().getPath();
            if (!fileEntry.getValue().isJsonObject()) {
                LOGGER.error("crop_climates: skipping malformed crop_climate file {} - not a JSON object", fileEntry.getKey());
                continue;
            }
            JsonObject file = fileEntry.getValue().getAsJsonObject();

            for (Map.Entry<String, JsonElement> keyEntry : file.entrySet()) {
                String key = keyEntry.getKey();
                try {
                    ResourceLocation blockId = key.indexOf(':') >= 0
                            ? ResourceLocation.parse(key)
                            : ResourceLocation.fromNamespaceAndPath(ownerNamespace, key);

                    java.util.Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(blockId);
                    if (block.isEmpty()) {
                        skipped++;
                        LOGGER.debug("crop_climates: {} in {} has no matching block ({}), skipping",
                                key, fileEntry.getKey(), blockId);
                        continue;
                    }

                    JsonObject entry = keyEntry.getValue().getAsJsonObject();
                    double[] temp = readRange(entry, "temperature");
                    double[] humidity = readRange(entry, "humidity");
                    boolean tree = entry.has("tree") && entry.get("tree").getAsBoolean();
                    boolean aquatic = entry.has("aquatic") && entry.get("aquatic").getAsBoolean();
                    String hookName = entry.has("hook") ? entry.get("hook").getAsString() : "CropGrowEvent";
                    ClimateBand.Hook hook = "randomTick".equals(hookName)
                            ? ClimateBand.Hook.RANDOM_TICK
                            : ClimateBand.Hook.CROP_GROW_EVENT;

                    ClimateBand band = new ClimateBand(temp[0], temp[1], humidity[0], humidity[1], tree, aquatic, hook);
                    blockBands.put(block.get(), band);
                    plants++;

                    if (hook == ClimateBand.Hook.RANDOM_TICK) {
                        if (tree) {
                            saplings.add(block.get());
                            saplingCount++;
                        } else {
                            ownTick.add(block.get());
                            ownTickCount++;
                        }
                    }

                    if (entry.has("item")) {
                        ResourceLocation itemId = ResourceLocation.parse(entry.get("item").getAsString());
                        java.util.Optional<Item> item = BuiltInRegistries.ITEM.getOptional(itemId);
                        if (item.isPresent()) {
                            // Same item can be shared by several blocks (e.g. a seed for
                            // several crop stages) - the shortest block id wins, matching
                            // the KubeJS client loader's tie-break.
                            String existing = itemToShortestBlockId.get(item.get());
                            String candidate = blockId.toString();
                            if (existing == null || candidate.length() < existing.length()) {
                                itemToShortestBlockId.put(item.get(), candidate);
                                itemBands.put(item.get(), band);
                            }
                        }
                    }
                } catch (RuntimeException ex) {
                    LOGGER.warn("crop_climates: skipping malformed entry '{}' in {} - {}", key, fileEntry.getKey(), ex.toString());
                }
            }
        }

        BLOCK_BANDS = Map.copyOf(blockBands);
        SAPLINGS = Set.copyOf(saplings);
        OWN_TICK = Set.copyOf(ownTick);
        ITEM_BANDS = Map.copyOf(itemBands);

        LOGGER.info("crop_climates: loaded {} plants ({} saplings, {} own-tick), {} skipped (no matching block)",
                plants, saplingCount, ownTickCount, skipped);
    }

    private static double[] readRange(JsonObject entry, String key) {
        JsonArray array = entry.getAsJsonArray(key);
        if (array == null || array.size() != 2) {
            throw new IllegalArgumentException("'" + key + "' must be a two-element array");
        }
        return new double[]{array.get(0).getAsDouble(), array.get(1).getAsDouble()};
    }
}
