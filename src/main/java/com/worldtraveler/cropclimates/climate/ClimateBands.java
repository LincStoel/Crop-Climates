package com.worldtraveler.cropclimates.climate;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.tags.TagKey;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import org.slf4j.Logger;

import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reload listener on folder {@code crop_climate}. One file per owning
 * namespace, keys are block paths: the file's own name supplies the namespace
 * for un-namespaced keys, so {@code data/<any>/crop_climate/minecraft.json},
 * key {@code "wheat"}, resolves to block {@code minecraft:wheat}. A key may
 * also be a full id ({@code "farmersdelight:tomatoes"}) or a block tag
 * ({@code "#c:crops"}).
 *
 * <p>Every datapack's copy of the same file is read, lowest pack first, and
 * merged key by key - a pack can retune one crop without copying the whole
 * file. Exact block keys always beat tag keys; among tags, the entry read
 * later wins.
 *
 * <p>Tags are not bound yet when reload listeners run, so {@link #apply} only
 * stores the parsed entries. {@link #resolve} binds them to blocks once the
 * server's tags are live ({@code TagsUpdatedEvent}), which also runs before
 * the datapack sync that feeds client tooltips.
 */
public final class ClimateBands extends SimplePreparableReloadListener<List<ClimateBands.Parsed>> {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final FileToIdConverter FILES = FileToIdConverter.json("crop_climate");

    /** One parsed entry, in read order. {@code tag} entries name a block tag in {@code id}. */
    public record Parsed(ResourceLocation id, boolean tag, BandSpec spec, ResourceLocation source) {
    }

    private static volatile List<Parsed> PARSED = List.of();
    private static volatile Map<Block, ClimateBand> BLOCK_BANDS = Map.of();
    private static volatile Set<Block> SAPLINGS = Set.of();
    private static volatile Set<Block> OWN_TICK = Set.of();
    private static volatile Map<Item, ClimateBand> ITEM_BANDS = Map.of();

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

    public static Map<Block, ClimateBand> blockBands() {
        return BLOCK_BANDS;
    }

    @Override
    protected List<Parsed> prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        List<Parsed> parsed = new ArrayList<>();
        for (Map.Entry<ResourceLocation, List<Resource>> stack : FILES.listMatchingResourceStacks(resourceManager).entrySet()) {
            ResourceLocation fileId = FILES.fileToId(stack.getKey());
            String ownerNamespace = fileId.getPath();
            for (Resource resource : stack.getValue()) {
                String source = fileId + " (" + resource.sourcePackId() + ")";
                JsonElement json;
                try (Reader reader = resource.openAsReader()) {
                    json = JsonParser.parseReader(reader);
                } catch (Exception ex) {
                    LOGGER.error("crop_climates: could not read crop_climate file {} - {}", source, ex.toString());
                    continue;
                }
                if (!json.isJsonObject()) {
                    LOGGER.error("crop_climates: skipping crop_climate file {} - not a JSON object", source);
                    continue;
                }
                readFile(json.getAsJsonObject(), ownerNamespace, fileId, source, parsed);
            }
        }
        return parsed;
    }

    private static void readFile(JsonObject file, String ownerNamespace, ResourceLocation fileId, String source, List<Parsed> out) {
        for (Map.Entry<String, JsonElement> entry : file.entrySet()) {
            String key = entry.getKey();
            boolean tag = key.startsWith("#");
            String raw = tag ? key.substring(1) : key;
            ResourceLocation id = raw.indexOf(':') >= 0
                    ? ResourceLocation.tryParse(raw)
                    : ResourceLocation.tryBuild(ownerNamespace, raw);
            if (id == null) {
                LOGGER.warn("crop_climates: skipping '{}' in {} - not a valid id", key, source);
                continue;
            }
            BandSpec.CODEC.parse(JsonOps.INSTANCE, entry.getValue())
                    .resultOrPartial(error -> LOGGER.warn("crop_climates: skipping '{}' in {} - {}", key, source, error))
                    .ifPresent(spec -> out.add(new Parsed(id, tag, spec, fileId)));
        }
    }

    @Override
    protected void apply(List<Parsed> parsed, ResourceManager resourceManager, ProfilerFiller profiler) {
        PARSED = List.copyOf(parsed);
    }

    /** Binds the parsed entries to blocks. Call once block tags are live. */
    public static void resolve() {
        Map<Block, ClimateBand> blockBands = new HashMap<>();
        Map<Block, ResourceLocation> tagOwner = new HashMap<>();
        Map<Block, Parsed> winners = new HashMap<>();
        Set<Block> exact = new HashSet<>();
        int skipped = 0;

        for (Parsed entry : PARSED) {
            if (entry.tag()) {
                continue;
            }
            Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(entry.id());
            if (block.isEmpty()) {
                skipped++;
                LOGGER.debug("crop_climates: {} in {} has no matching block, skipping", entry.id(), entry.source());
                continue;
            }
            exact.add(block.get());
            winners.put(block.get(), entry);
        }

        for (Parsed entry : PARSED) {
            if (!entry.tag()) {
                continue;
            }
            TagKey<Block> tagKey = TagKey.create(Registries.BLOCK, entry.id());
            Optional<HolderSet.Named<Block>> members = BuiltInRegistries.BLOCK.getTag(tagKey);
            if (members.isEmpty()) {
                LOGGER.debug("crop_climates: tag #{} in {} is empty or unknown, skipping", entry.id(), entry.source());
                continue;
            }
            for (Holder<Block> holder : members.get()) {
                Block block = holder.value();
                if (exact.contains(block)) {
                    continue;
                }
                ResourceLocation previous = tagOwner.put(block, entry.id());
                if (previous != null && !previous.equals(entry.id())) {
                    LOGGER.warn("crop_climates: {} is in both #{} and #{} - using #{}, the one read later",
                            BuiltInRegistries.BLOCK.getKey(block), previous, entry.id(), entry.id());
                }
                winners.put(block, entry);
            }
        }

        Set<Block> saplings = new HashSet<>();
        Set<Block> ownTick = new HashSet<>();
        Map<Item, String> itemToShortestBlockId = new HashMap<>();
        Map<Item, ClimateBand> itemBands = new HashMap<>();

        for (Map.Entry<Block, Parsed> winner : winners.entrySet()) {
            Block block = winner.getKey();
            BandSpec spec = winner.getValue().spec();
            ClimateBand band = spec.toBand(regressesByDefault(block, spec.tree()));
            blockBands.put(block, band);

            if (band.hook() == ClimateBand.Hook.RANDOM_TICK) {
                (band.tree() ? saplings : ownTick).add(block);
            }

            Item item = spec.item().flatMap(BuiltInRegistries.ITEM::getOptional).orElseGet(block::asItem);
            if (item != Items.AIR) {
                // Same item can be shared by several blocks (e.g. a seed for
                // several crop stages) - the shortest block id wins.
                String candidate = BuiltInRegistries.BLOCK.getKey(block).toString();
                String existing = itemToShortestBlockId.get(item);
                if (existing == null || candidate.length() < existing.length()
                        || (candidate.length() == existing.length() && candidate.compareTo(existing) < 0)) {
                    itemToShortestBlockId.put(item, candidate);
                    itemBands.put(item, band);
                }
            }
        }

        BLOCK_BANDS = Map.copyOf(blockBands);
        SAPLINGS = Set.copyOf(saplings);
        OWN_TICK = Set.copyOf(ownTick);
        ITEM_BANDS = Map.copyOf(itemBands);

        LOGGER.info("crop_climates: loaded {} plants ({} saplings, {} own-tick), {} skipped (no matching block)",
                blockBands.size(), saplings.size(), ownTick.size(), skipped);
    }

    /**
     * Staged crops and saplings wilt back by default. Plants whose {@code age}
     * is only a growth counter (sugar cane, cactus, bamboo, vines) do not -
     * losing a step there would mean nothing.
     */
    static boolean regressesByDefault(Block block, boolean tree) {
        return tree
                || block instanceof CropBlock
                || block instanceof StemBlock
                || block instanceof SweetBerryBushBlock
                || block instanceof NetherWartBlock
                || block instanceof CocoaBlock;
    }
}
