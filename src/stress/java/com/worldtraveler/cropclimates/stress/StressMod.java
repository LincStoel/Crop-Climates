package com.worldtraveler.cropclimates.stress;

import com.worldtraveler.cropclimates.stress.net.StressNet;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Dev-only stress harness for crop_climates. Loaded only by the
 * {@code stressServer}/{@code stressClient} runs; never packaged.
 *
 * <p>Everything is driven by {@code /ccstress} (see {@link StressCommands})
 * and the scripts in {@code tools/stress/}.
 */
@Mod(StressMod.MOD_ID)
public final class StressMod {

    public static final String MOD_ID = "crop_climates_stress";

    /** Forced tickets with {@code ticking = true}: chunks random-tick with no player nearby. */
    public static final TicketController TICKETS =
            new TicketController(ResourceLocation.fromNamespaceAndPath(MOD_ID, "force"));

    public StressMod(IEventBus modBus, ModContainer container) {
        modBus.addListener(RegisterTicketControllersEvent.class, event -> event.register(TICKETS));
        modBus.addListener(RegisterPayloadHandlersEvent.class, StressNet::register);

        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class,
                event -> StressCommands.register(event.getDispatcher()));
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false, ServerTickEvent.Pre.class, TickTimer::onPre);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, ServerTickEvent.Post.class, TickTimer::onPost);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerTickEvent.Post.class, Driver::onServerTick);
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, event -> {
            Results.log("server started");
            Builders.restore();
        });
    }
}
