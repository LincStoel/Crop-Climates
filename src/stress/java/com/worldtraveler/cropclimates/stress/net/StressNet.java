package com.worldtraveler.cropclimates.stress.net;

import com.worldtraveler.cropclimates.stress.StressMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Server -> client instructions for the stress client: screenshots and tooltip captures. */
public final class StressNet {

    /** {@code action} is "shot" (arg = file name) or "tooltip" (arg = item id) or "log" (arg = marker). */
    public record Instruct(String action, String arg) implements CustomPacketPayload {
        public static final Type<Instruct> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(StressMod.MOD_ID, "instruct"));
        public static final StreamCodec<ByteBuf, Instruct> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Instruct::action,
                ByteBufCodecs.STRING_UTF8, Instruct::arg,
                Instruct::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private StressNet() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar(StressMod.MOD_ID).versioned("1").optional().playToClient(Instruct.TYPE, Instruct.STREAM_CODEC,
                (payload, context) -> {
                    if (FMLEnvironment.dist.isClient()) {
                        context.enqueueWork(() -> com.worldtraveler.cropclimates.stress.client.ClientProbe.handle(payload));
                    }
                });
    }
}
