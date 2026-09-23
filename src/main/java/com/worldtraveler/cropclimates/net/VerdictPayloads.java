package com.worldtraveler.cropclimates.net;

import com.worldtraveler.cropclimates.CropClimates;
import com.worldtraveler.cropclimates.CropClimatesConfig;
import com.worldtraveler.cropclimates.climate.ClimateBand;
import com.worldtraveler.cropclimates.climate.ClimateBands;
import com.worldtraveler.cropclimates.growth.GrowthGovernor;
import com.worldtraveler.cropclimates.report.Verdict;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Alt tooltip's "Here: would thrive" line. The client asks about one item;
 * the server scores that item's band at the player's feet through the same
 * {@link GrowthGovernor} path crops use - cached temperature, greenhouse
 * lookup - and answers with the verdict tier plus where the humidity came
 * from ({@link Where}) and its value, so the player can see a greenhouse is
 * being counted. The client throttles and caches; the server additionally
 * caps each player's request rate.
 */
public final class VerdictPayloads {

    /** Verdict id meaning "no answer right now" (no band, temperature unavailable, throttled). */
    public static final int UNKNOWN = -1;

    /** Where the humidity behind a verdict came from. */
    public enum Where { OUTDOOR, RAINING, GREENHOUSE, UNDERWATER }

    private static final int MAX_REQUESTS_PER_SECOND = 8;
    private static final Map<UUID, long[]> RATE = new ConcurrentHashMap<>();

    private VerdictPayloads() {
    }

    public record Request(ResourceLocation item) implements CustomPacketPayload {
        public static final Type<Request> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(CropClimates.MOD_ID, "verdict_request"));
        public static final StreamCodec<ByteBuf, Request> STREAM_CODEC =
                ResourceLocation.STREAM_CODEC.map(Request::new, Request::item);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * @param where    a {@link Where} ordinal (meaningless when {@code verdict} is {@link #UNKNOWN})
     * @param humidity the humidity the band was scored against, in whole percent
     */
    public record Response(ResourceLocation item, int verdict, int where, int humidity) implements CustomPacketPayload {
        public static final Type<Response> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(CropClimates.MOD_ID, "verdict_response"));
        public static final StreamCodec<ByteBuf, Response> STREAM_CODEC = StreamCodec.composite(
                ResourceLocation.STREAM_CODEC, Response::item,
                ByteBufCodecs.VAR_INT, Response::verdict,
                ByteBufCodecs.VAR_INT, Response::where,
                ByteBufCodecs.VAR_INT, Response::humidity,
                Response::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void handleRequest(Request request, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player) || !allow(player)) {
                return;
            }
            PacketDistributor.sendToPlayer(player, verdictFor(player, request.item()));
        });
    }

    private static boolean allow(ServerPlayer player) {
        long second = player.serverLevel().getGameTime() / 20;
        long[] window = RATE.computeIfAbsent(player.getUUID(), k -> new long[]{second, 0});
        if (window[0] != second) {
            window[0] = second;
            window[1] = 0;
        }
        return ++window[1] <= MAX_REQUESTS_PER_SECOND;
    }

    private static Response verdictFor(ServerPlayer player, ResourceLocation itemId) {
        Response unknown = new Response(itemId, UNKNOWN, 0, 0);
        ClimateBand band = BuiltInRegistries.ITEM.getOptional(itemId).map(ClimateBands.itemBands()::get).orElse(null);
        if (band == null) {
            return unknown;
        }
        BlockPos pos = GrowthGovernor.standingCell(player);
        GrowthGovernor.Conditions conditions = GrowthGovernor.resolve(player.level(), pos, band);
        if (conditions == null) {
            return unknown;
        }
        boolean sapling = band.tree() && band.hook() == ClimateBand.Hook.RANDOM_TICK;
        double total = GrowthGovernor.score(band, conditions, sapling).total();
        Where where = switch (conditions.waiver()) {
            case SUBMERGED -> Where.UNDERWATER;
            case ENCLOSED -> Where.GREENHOUSE;
            case NONE -> conditions.raining() ? Where.RAINING : Where.OUTDOOR;
        };
        return new Response(itemId, Verdict.of(total, CropClimatesConfig.GROWTH_MAX.get()).ordinal(),
                where.ordinal(), (int) Math.round(conditions.humidity() * 100));
    }

    public static void forget(UUID player) {
        RATE.remove(player);
    }
}
