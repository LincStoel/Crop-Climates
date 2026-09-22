package com.worldtraveler.cropclimates.client;

import com.worldtraveler.cropclimates.CropClimates;
import com.worldtraveler.cropclimates.climate.TemperatureUnits;
import com.worldtraveler.cropclimates.net.ClimateSyncPayload;
import com.worldtraveler.cropclimates.net.VerdictPayloads;
import com.worldtraveler.cropclimates.report.Verdict;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Alt-gated "growing conditions" tooltip, fed by {@link ClimateSyncPayload}
 * for the bands and by {@link VerdictPayloads} for the "Here: would thrive"
 * line. Verdicts are cached per item for {@link #VERDICT_TTL_MS} and requests
 * are capped at {@link #MAX_REQUESTS_PER_SECOND}, so hovering around an
 * inventory costs a handful of tiny packets at most.
 *
 * <p>{@code @EventBusSubscriber(value = Dist.CLIENT)} is what wires
 * {@link #onItemTooltip} up, and keeps this class off dedicated servers.
 */
@EventBusSubscriber(modid = CropClimates.MOD_ID, value = Dist.CLIENT)
public final class CropTooltips {

    private static final long VERDICT_TTL_MS = 2000;
    private static final int MAX_REQUESTS_PER_SECOND = 4;

    private record CachedVerdict(VerdictPayloads.Response response, long at) {
    }

    private static volatile Map<Item, ClimateSyncPayload.TipBand> BANDS = Map.of();
    private static final Map<Item, CachedVerdict> VERDICTS = new ConcurrentHashMap<>();
    private static final Map<Item, Long> PENDING = new ConcurrentHashMap<>();
    private static long windowStart;
    private static int windowCount;

    private CropTooltips() {
    }

    public static void handleSync(ClimateSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Map<Item, ClimateSyncPayload.TipBand> resolved = new HashMap<>();
            for (Map.Entry<ResourceLocation, ClimateSyncPayload.TipBand> entry : payload.bands().entrySet()) {
                BuiltInRegistries.ITEM.getOptional(entry.getKey()).ifPresent(item -> resolved.put(item, entry.getValue()));
            }
            BANDS = Map.copyOf(resolved);
            VERDICTS.clear();
        });
    }

    public static void handleVerdict(VerdictPayloads.Response response, IPayloadContext context) {
        context.enqueueWork(() -> BuiltInRegistries.ITEM.getOptional(response.item()).ifPresent(item -> {
            PENDING.remove(item);
            VERDICTS.put(item, new CachedVerdict(response, System.currentTimeMillis()));
        }));
    }

    private static MutableComponent tip(String path, Object... args) {
        return Component.translatable("tooltip." + CropClimates.MOD_ID + "." + path, args);
    }

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        Item item = event.getItemStack().getItem();
        ClimateSyncPayload.TipBand band = BANDS.get(item);
        if (band == null) {
            return;
        }

        if (!Screen.hasAltDown()) {
            event.getToolTip().add(tip("hold_alt", Component.literal("Alt").withStyle(ChatFormatting.YELLOW))
                    .withStyle(ChatFormatting.GRAY));
            return;
        }

        var units = TemperatureUnits.forClient();
        event.getToolTip().add(tip("conditions").withStyle(ChatFormatting.DARK_GREEN));
        event.getToolTip().add(tip("temp", Component.literal(TemperatureUnits.formatRange(band.tempLo(), band.tempHi(), units))
                .withStyle(ChatFormatting.WHITE)).withStyle(ChatFormatting.GRAY));
        event.getToolTip().add(tip("humidity", Component.literal(
                Math.round(band.moistLo() * 100) + "–" + Math.round(band.moistHi() * 100) + "%")
                .withStyle(ChatFormatting.WHITE)).withStyle(ChatFormatting.GRAY));
        if (band.tree()) {
            event.getToolTip().add(tip("tree").withStyle(ChatFormatting.DARK_GRAY));
        }
        if (band.aquatic()) {
            event.getToolTip().add(tip("aquatic").withStyle(ChatFormatting.DARK_GRAY));
        }
        event.getToolTip().add(hereLine(item));
    }

    private static Component hereLine(Item item) {
        long now = System.currentTimeMillis();
        CachedVerdict cached = VERDICTS.get(item);
        if (cached == null || now - cached.at() > VERDICT_TTL_MS) {
            request(item, now);
        }
        if (cached == null) {
            return tip("here_checking").withStyle(ChatFormatting.DARK_GRAY);
        }
        VerdictPayloads.Response response = cached.response();
        if (response.verdict() == VerdictPayloads.UNKNOWN) {
            return tip("here_unknown").withStyle(ChatFormatting.DARK_GRAY);
        }
        VerdictPayloads.Where[] places = VerdictPayloads.Where.values();
        VerdictPayloads.Where where = places[Math.floorMod(response.where(), places.length)];
        Component place = tip("where." + where.name().toLowerCase(Locale.ROOT), response.humidity() + "%")
                .withStyle(where == VerdictPayloads.Where.GREENHOUSE ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY);
        return tip("here", Verdict.byId(response.verdict()).wouldLabel(), place).withStyle(ChatFormatting.GRAY);
    }

    private static void request(Item item, long now) {
        if (Minecraft.getInstance().getConnection() == null) {
            return;
        }
        Long pendingSince = PENDING.get(item);
        if (pendingSince != null && now - pendingSince < VERDICT_TTL_MS) {
            return;
        }
        if (now - windowStart >= 1000) {
            windowStart = now;
            windowCount = 0;
        }
        if (windowCount >= MAX_REQUESTS_PER_SECOND) {
            return;
        }
        windowCount++;
        PENDING.put(item, now);
        PacketDistributor.sendToServer(new VerdictPayloads.Request(BuiltInRegistries.ITEM.getKey(item)));
    }
}
