package com.worldtraveler.cropclimates.stress.mixin;

import com.worldtraveler.cropclimates.greenhouse.GreenhouseRegistry;
import com.worldtraveler.cropclimates.greenhouse.Room;
import com.worldtraveler.cropclimates.greenhouse.RoomScan;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;

@Mixin(value = GreenhouseRegistry.class, remap = false)
public interface GreenhouseRegistryAccessor {

    @Accessor("activeScan")
    RoomScan cc$activeScan();

    @Accessor("rooms")
    Map<UUID, Room> cc$rooms();

    @Accessor("probes")
    Map<UUID, ?> cc$probes();

    @Accessor("queue")
    ArrayDeque<UUID> cc$queue();
}
