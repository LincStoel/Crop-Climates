package com.worldtraveler.cropclimates.stress.client;

import com.worldtraveler.cropclimates.stress.StressMod;
import com.worldtraveler.cropclimates.stress.net.StressNet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;

/**
 * Client half of the harness: once a second it appends FPS, particle count
 * and position to {@code stress-client.csv}; on instruction from the server
 * it takes a screenshot or captures an item's tooltip lines (with Alt forced
 * down, see {@code ScreenMixin}) into {@code stress-tooltips.txt}.
 */
@EventBusSubscriber(modid = StressMod.MOD_ID, value = Dist.CLIENT)
public final class ClientProbe {

    public static volatile boolean forceAlt;
    private static int ticks;
    private static String marker = "";

    private ClientProbe() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || ++ticks % 20 != 0) {
            return;
        }
        String line = String.format(Locale.ROOT, "%d,%s,%d,%s,%.1f,%.1f,%.1f,%s%n", System.currentTimeMillis(), marker,
                mc.getFps(), mc.particleEngine.countParticles(), mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                mc.levelRenderer.getEntityStatistics().replace(',', ' '));
        append("stress-client.csv", line);
    }

    public static void handle(StressNet.Instruct payload) {
        Minecraft mc = Minecraft.getInstance();
        switch (payload.action()) {
            case "shot" -> Screenshot.grab(mc.gameDirectory, payload.arg() + ".png", mc.getMainRenderTarget(),
                    message -> append("stress-shots.txt", payload.arg() + ": " + message.getString() + System.lineSeparator()));
            case "tooltip" -> {
                Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(payload.arg()));
                StringBuilder out = new StringBuilder("== " + payload.arg() + " @" + marker + System.lineSeparator());
                for (boolean alt : new boolean[]{false, true}) {
                    forceAlt = alt;
                    try {
                        List<Component> lines = new ItemStack(item).getTooltipLines(
                                Item.TooltipContext.of(mc.level), mc.player, TooltipFlag.NORMAL);
                        out.append(alt ? "[alt]" : "[plain]").append(System.lineSeparator());
                        for (Component c : lines) {
                            out.append("  ").append(c.getString()).append(System.lineSeparator());
                        }
                    } finally {
                        forceAlt = false;
                    }
                }
                append("stress-tooltips.txt", out.toString());
            }
            case "log" -> marker = payload.arg();
            default -> {
            }
        }
    }

    private static void append(String file, String text) {
        Path path = Minecraft.getInstance().gameDirectory.toPath().resolve(file);
        try {
            Files.writeString(path, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }
}
