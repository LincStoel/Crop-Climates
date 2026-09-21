package com.worldtraveler.cropclimates.client;

import com.worldtraveler.cropclimates.CropClimates;
import com.worldtraveler.cropclimates.net.ClimateSyncPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.HashMap;
import java.util.Map;

/**
 * Alt-gated "growing conditions" tooltip, fed by {@link ClimateSyncPayload}
 * instead of a second on-disk datapack read - see that class's javadoc.
 * Same formatting and colours as {@code crop_tooltips.js}.
 *
 * <p>{@code @EventBusSubscriber(value = Dist.CLIENT)} is what actually wires
 * {@link #onItemTooltip} up - FML scans and registers it automatically, and
 * (just as importantly) never loads this class on a dedicated server, where
 * the {@link Screen} reference below would otherwise be unresolvable.
 */
@EventBusSubscriber(modid = CropClimates.MOD_ID, value = Dist.CLIENT)
public final class CropTooltips {

    private static volatile Map<Item, ClimateSyncPayload.TipBand> BANDS = Map.of();

    private CropTooltips() {
    }

    public static void handleSync(ClimateSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Map<Item, ClimateSyncPayload.TipBand> resolved = new HashMap<>();
            for (Map.Entry<ResourceLocation, ClimateSyncPayload.TipBand> entry : payload.bands().entrySet()) {
                BuiltInRegistries.ITEM.getOptional(entry.getKey()).ifPresent(item -> resolved.put(item, entry.getValue()));
            }
            BANDS = Map.copyOf(resolved);
        });
    }

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        ClimateSyncPayload.TipBand band = BANDS.get(event.getItemStack().getItem());
        if (band == null) {
            return;
        }

        if (!Screen.hasAltDown()) {
            event.getToolTip().add(Component.literal("Hold ")
                    .withStyle(ChatFormatting.GRAY)
                    .append(Component.literal("Alt ").withStyle(ChatFormatting.YELLOW))
                    .append(Component.literal("for growing conditions.").withStyle(ChatFormatting.GRAY)));
            return;
        }

        event.getToolTip().add(Component.literal("Growing conditions").withStyle(ChatFormatting.DARK_GREEN));
        event.getToolTip().add(Component.literal("  Temp: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(tempRange(band)).withStyle(ChatFormatting.WHITE)));
        event.getToolTip().add(Component.literal("  Humidity: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(humidityRange(band)).withStyle(ChatFormatting.WHITE)));
        if (band.tree()) {
            event.getToolTip().add(Component.literal("  Trees tolerate a wider spread.").withStyle(ChatFormatting.DARK_GRAY));
        }
        if (band.aquatic()) {
            event.getToolTip().add(Component.literal("  Ignores humidity while underwater.").withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    private static String tempRange(ClimateSyncPayload.TipBand band) {
        return Math.round(band.tempLo()) + "–" + Math.round(band.tempHi()) + "°F";
    }

    private static String humidityRange(ClimateSyncPayload.TipBand band) {
        return Math.round(band.moistLo() * 100) + "–" + Math.round(band.moistHi() * 100) + "%";
    }
}
