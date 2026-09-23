package com.worldtraveler.cropclimates.client;

import com.worldtraveler.cropclimates.CropClimates;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;

/** Client-only mod-bus registration: renderers, standalone models, particle providers. */
@EventBusSubscriber(modid = CropClimates.MOD_ID, value = Dist.CLIENT)
public final class ClientSetup {

    private ClientSetup() {
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(CropClimates.HYGROMETER_ENTITY.get(), HygrometerRenderer::new);
    }

    @SubscribeEvent
    public static void registerModels(ModelEvent.RegisterAdditional event) {
        event.register(HygrometerRenderer.FACE_MODEL);
        event.register(HygrometerRenderer.DIAL_MODEL);
        event.register(HygrometerRenderer.NEEDLE_MODEL);
        event.register(HygrometerRenderer.LIGHT_MODEL);
    }

    @SubscribeEvent
    public static void registerParticles(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(CropClimates.WILT_COLD.get(), sprites -> new WiltParticle.Provider(sprites, 0.62F, 0.80F, 0.95F));
        event.registerSpriteSet(CropClimates.WILT_HOT.get(), sprites -> new WiltParticle.Provider(sprites, 0.55F, 0.52F, 0.50F));
        event.registerSpriteSet(CropClimates.WILT_DRY.get(), sprites -> new WiltParticle.Provider(sprites, 0.80F, 0.68F, 0.48F));
        event.registerSpriteSet(CropClimates.WILT_WET.get(), sprites -> new WiltParticle.Provider(sprites, 0.38F, 0.50F, 0.34F));
        event.registerSpriteSet(CropClimates.GREENHOUSE_DUST.get(), GreenhouseDustParticle.Provider::new);
    }
}
