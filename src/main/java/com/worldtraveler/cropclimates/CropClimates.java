package com.worldtraveler.cropclimates;

import com.worldtraveler.cropclimates.client.CropTooltips;
import com.worldtraveler.cropclimates.climate.BiomeMoisture;
import com.worldtraveler.cropclimates.climate.ClimateBand;
import com.worldtraveler.cropclimates.climate.ClimateBands;
import com.worldtraveler.cropclimates.climate.ClimateSampler;
import com.worldtraveler.cropclimates.command.CropClimatesCommand;
import com.worldtraveler.cropclimates.entity.HygrometerEntity;
import com.worldtraveler.cropclimates.greenhouse.Greenhouses;
import com.worldtraveler.cropclimates.growth.CropGrowHandlers;
import com.worldtraveler.cropclimates.growth.Regression;
import com.worldtraveler.cropclimates.item.HygrometerItem;
import com.worldtraveler.cropclimates.item.SoilTesterItem;
import com.worldtraveler.cropclimates.net.ClimateSyncPayload;
import com.worldtraveler.cropclimates.net.VerdictPayloads;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.level.block.CropGrowEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Mod(CropClimates.MOD_ID)
public final class CropClimates {

    public static final String MOD_ID = "crop_climates";

    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);
    private static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(Registries.ENTITY_TYPE, MOD_ID);
    private static final DeferredRegister<ParticleType<?>> PARTICLES = DeferredRegister.create(Registries.PARTICLE_TYPE, MOD_ID);

    public static final DeferredItem<SoilTesterItem> SOIL_TESTER = ITEMS.registerItem(
            "soil_tester", SoilTesterItem::new, new Item.Properties().stacksTo(1));
    public static final DeferredItem<HygrometerItem> HYGROMETER = ITEMS.registerItem(
            "hygrometer", HygrometerItem::new, new Item.Properties());

    public static final DeferredHolder<EntityType<?>, EntityType<HygrometerEntity>> HYGROMETER_ENTITY =
            ENTITY_TYPES.register("hygrometer", () -> EntityType.Builder.<HygrometerEntity>of(HygrometerEntity::new, MobCategory.MISC)
                    .sized(0.5F, 0.5F)
                    .eyeHeight(0.0F)
                    .clientTrackingRange(10)
                    .updateInterval(Integer.MAX_VALUE)
                    .build(MOD_ID + ":hygrometer"));

    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> WILT_COLD = particle("wilt_cold");
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> WILT_HOT = particle("wilt_hot");
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> WILT_DRY = particle("wilt_dry");
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> WILT_WET = particle("wilt_wet");
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> GREENHOUSE_DUST = particle("greenhouse_dust");

    private static DeferredHolder<ParticleType<?>, SimpleParticleType> particle(String name) {
        return PARTICLES.register(name, () -> new SimpleParticleType(false));
    }

    public CropClimates(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, CropClimatesConfig.SPEC);

        ITEMS.register(modBus);
        ENTITY_TYPES.register(modBus);
        PARTICLES.register(modBus);
        modBus.addListener(RegisterPayloadHandlersEvent.class, this::registerPayloads);
        modBus.addListener(BuildCreativeModeTabContentsEvent.class, this::addToCreativeTab);

        NeoForge.EVENT_BUS.addListener(AddReloadListenerEvent.class, this::addReloadListeners);
        NeoForge.EVENT_BUS.addListener(CropGrowEvent.Pre.class, CropGrowHandlers::onPre);
        NeoForge.EVENT_BUS.addListener(CropGrowEvent.Post.class, CropGrowHandlers::onPost);
        NeoForge.EVENT_BUS.addListener(OnDatapackSyncEvent.class, this::onDatapackSync);
        NeoForge.EVENT_BUS.addListener(TagsUpdatedEvent.class, this::onTagsUpdated);
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, event -> clearCaches());
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, event -> CropClimatesCommand.register(event.getDispatcher()));
        NeoForge.EVENT_BUS.addListener(LevelEvent.Load.class, Greenhouses::onLevelLoad);
        NeoForge.EVENT_BUS.addListener(LevelEvent.Unload.class, Greenhouses::onLevelUnload);
        NeoForge.EVENT_BUS.addListener(LevelTickEvent.Post.class, this::onLevelTick);
        NeoForge.EVENT_BUS.addListener(BlockEvent.NeighborNotifyEvent.class, HygrometerEntity::onNeighborNotify);
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent.class,
                event -> VerdictPayloads.forget(event.getEntity().getUUID()));
    }

    private void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) {
            Greenhouses.get(level).tick(level);
            Regression.flush(level);
        }
    }

    /**
     * Singleplayer keeps statics alive across worlds, so every cache keyed by
     * game time or world must be dropped when a server stops - and the
     * switches a failure flips, whose logs promise a restart clears them.
     */
    private static void clearCaches() {
        ClimateSampler.clear();
        Greenhouses.clear();
        Regression.clear();
        CropGrowHandlers.reset();
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
        var registrar = event.registrar(MOD_ID).versioned("4");
        // Lambdas, not method references: CropTooltips is client-only and must
        // not be loaded on a dedicated server just by registering its handlers.
        registrar.playToClient(ClimateSyncPayload.TYPE, ClimateSyncPayload.STREAM_CODEC,
                (payload, context) -> CropTooltips.handleSync(payload, context));
        registrar.playToServer(VerdictPayloads.Request.TYPE, VerdictPayloads.Request.STREAM_CODEC, VerdictPayloads::handleRequest);
        registrar.playToClient(VerdictPayloads.Response.TYPE, VerdictPayloads.Response.STREAM_CODEC,
                (payload, context) -> CropTooltips.handleVerdict(payload, context));
    }

    private void addToCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(SOIL_TESTER);
            event.accept(HYGROMETER);
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
        Map<ResourceLocation, ClimateSyncPayload.TipBand> bands = new HashMap<>();
        for (Map.Entry<Item, ClimateBand> entry : ClimateBands.itemBands().entrySet()) {
            ClimateBand band = entry.getValue();
            bands.put(BuiltInRegistries.ITEM.getKey(entry.getKey()),
                    new ClimateSyncPayload.TipBand(band.tempLo(), band.tempHi(), band.moistLo(), band.moistHi(), band.tree(), band.aquatic()));
        }
        Set<ResourceLocation> blocks = new HashSet<>();
        for (Block block : ClimateBands.blockBands().keySet()) {
            blocks.add(BuiltInRegistries.BLOCK.getKey(block));
        }
        return new ClimateSyncPayload(bands, blocks);
    }
}
