package com.worldtraveler.cropclimates;

import com.worldtraveler.cropclimates.client.CropTooltips;
import com.worldtraveler.cropclimates.climate.BiomeMoisture;
import com.worldtraveler.cropclimates.climate.ClimateBand;
import com.worldtraveler.cropclimates.climate.ClimateBands;
import com.worldtraveler.cropclimates.climate.ClimateSampler;
import com.worldtraveler.cropclimates.climate.EnclosureSampler;
import com.worldtraveler.cropclimates.growth.CropGrowHandlers;
import com.worldtraveler.cropclimates.item.SoilTesterItem;
import com.worldtraveler.cropclimates.net.ClimateSyncPayload;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.level.block.CropGrowEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.HashMap;
import java.util.Map;

@Mod(CropClimates.MOD_ID)
public final class CropClimates {

    public static final String MOD_ID = "crop_climates";

    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);
    public static final DeferredItem<SoilTesterItem> SOIL_TESTER = ITEMS.registerItem(
            "soil_tester", SoilTesterItem::new, new Item.Properties().stacksTo(1));

    public CropClimates(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, CropClimatesConfig.SPEC);

        ITEMS.register(modBus);
        modBus.addListener(RegisterPayloadHandlersEvent.class, this::registerPayloads);
        modBus.addListener(BuildCreativeModeTabContentsEvent.class, this::addToCreativeTab);

        NeoForge.EVENT_BUS.addListener(AddReloadListenerEvent.class, this::addReloadListeners);
        NeoForge.EVENT_BUS.addListener(CropGrowEvent.Pre.class, CropGrowHandlers::onPre);
        NeoForge.EVENT_BUS.addListener(CropGrowEvent.Post.class, CropGrowHandlers::onPost);
        NeoForge.EVENT_BUS.addListener(OnDatapackSyncEvent.class, this::onDatapackSync);
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, event -> clearCaches());
        NeoForge.EVENT_BUS.addListener(TagsUpdatedEvent.class, this::onTagsUpdated);
    }

    /**
     * Singleplayer keeps statics alive across worlds, so every cache keyed by
     * game time must be dropped when a server stops.
     */
    private static void clearCaches() {
        ClimateSampler.clear();
        EnclosureSampler.clear();
    }

    /**
     * Server tags are bound now (and this runs before {@code OnDatapackSyncEvent}),
     * so tag-keyed crop entries and tag-based moisture overrides can resolve.
     */
    private void onTagsUpdated(TagsUpdatedEvent event) {
        if (event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) {
            ClimateBands.resolve();
            BiomeMoisture.invalidate();
        }
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar(MOD_ID).versioned("1")
                .playToClient(ClimateSyncPayload.TYPE, ClimateSyncPayload.STREAM_CODEC, CropTooltips::handleSync);
    }

    private void addToCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(SOIL_TESTER);
        }
    }

    private void addReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new ClimateBands());
        event.addListener(new BiomeMoisture());
    }

    private void onDatapackSync(OnDatapackSyncEvent event) {
        ClimateSyncPayload payload = buildSyncPayload();
        if (event.getPlayer() != null) {
            PacketDistributor.sendToPlayer(event.getPlayer(), payload);
        } else {
            event.getPlayerList().getPlayers().forEach(player -> PacketDistributor.sendToPlayer(player, payload));
        }
    }

    private static ClimateSyncPayload buildSyncPayload() {
        Map<net.minecraft.resources.ResourceLocation, ClimateSyncPayload.TipBand> bands = new HashMap<>();
        for (Map.Entry<net.minecraft.world.item.Item, ClimateBand> entry : ClimateBands.itemBands().entrySet()) {
            ClimateBand band = entry.getValue();
            bands.put(BuiltInRegistries.ITEM.getKey(entry.getKey()),
                    new ClimateSyncPayload.TipBand(band.tempLo(), band.tempHi(), band.moistLo(), band.moistHi(), band.tree(), band.aquatic()));
        }
        return new ClimateSyncPayload(bands);
    }
}
