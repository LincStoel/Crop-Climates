package com.worldtraveler.cropclimates.net;

import com.worldtraveler.cropclimates.CropClimates;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Server -> client band table, sent on {@code OnDatapackSyncEvent} (login and
 * {@code /reload}), so the client never reads datapacks itself. Correct on
 * dedicated servers and for packs that override the data.
 *
 * @param bands  tooltip bands by item
 * @param blocks every block with a band, so Jade only asks about those
 */
public record ClimateSyncPayload(Map<ResourceLocation, TipBand> bands, Set<ResourceLocation> blocks)
        implements CustomPacketPayload {

    /**
     * Everything {@code CropTooltips} needs: rounded bands plus the tree and
     * aquatic flags. Six component/getter pairs is exactly what
     * {@link StreamCodec#composite} tops out at - the next field forces a
     * hand-written codec.
     */
    public record TipBand(double tempLo, double tempHi, double moistLo, double moistHi, boolean tree, boolean aquatic) {
        public static final StreamCodec<ByteBuf, TipBand> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.DOUBLE, TipBand::tempLo,
                ByteBufCodecs.DOUBLE, TipBand::tempHi,
                ByteBufCodecs.DOUBLE, TipBand::moistLo,
                ByteBufCodecs.DOUBLE, TipBand::moistHi,
                ByteBufCodecs.BOOL, TipBand::tree,
                ByteBufCodecs.BOOL, TipBand::aquatic,
                TipBand::new
        );
    }

    public static final Type<ClimateSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CropClimates.MOD_ID, "climate_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClimateSyncPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.map(HashMap::new, ResourceLocation.STREAM_CODEC, TipBand.STREAM_CODEC),
            ClimateSyncPayload::bands,
            ByteBufCodecs.collection(HashSet::new, ResourceLocation.STREAM_CODEC),
            ClimateSyncPayload::blocks,
            ClimateSyncPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
