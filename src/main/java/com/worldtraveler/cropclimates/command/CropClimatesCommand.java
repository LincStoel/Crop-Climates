package com.worldtraveler.cropclimates.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.worldtraveler.cropclimates.CropClimates;
import com.worldtraveler.cropclimates.climate.BiomeMoisture;
import com.worldtraveler.cropclimates.climate.ClimateBands;
import com.worldtraveler.cropclimates.climate.ClimateSampler;
import com.worldtraveler.cropclimates.climate.TemperatureUnits;
import com.worldtraveler.cropclimates.greenhouse.GreenhouseRegistry;
import com.worldtraveler.cropclimates.greenhouse.Greenhouses;
import com.worldtraveler.cropclimates.growth.CropGrowHandlers;
import com.worldtraveler.cropclimates.report.ClimateReport;
import com.worldtraveler.cropclimates.report.Reports;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code /cropclimates} (permission level 2):
 * <ul>
 *   <li>{@code explain <pos>} - the Soil Tester report for any position.</li>
 *   <li>{@code audit} - growable-looking blocks with no climate band, grouped by
 *       namespace, and biomes whose moisture overrides tie.</li>
 *   <li>{@code status} - kill switches, error count, caches, greenhouses.</li>
 * </ul>
 */
public final class CropClimatesCommand {

    private static final int AUDIT_NAMES_PER_NAMESPACE = 24;

    private CropClimatesCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("cropclimates")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("explain")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(CropClimatesCommand::explain)))
                .then(Commands.literal("audit").executes(CropClimatesCommand::audit))
                .then(Commands.literal("status").executes(CropClimatesCommand::status)));
    }

    private static MutableComponent key(String path, Object... args) {
        return Component.translatable("command." + CropClimates.MOD_ID + "." + path, args);
    }

    private static int explain(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
        Component title = key("explain.title", pos.getX() + " " + pos.getY() + " " + pos.getZ());
        Reports.climate(source.getLevel(), pos, title, TemperatureUnits.forPlayer(source.getPlayer()))
                .send(line -> source.sendSuccess(() -> line, false));
        return 1;
    }

    private static int audit(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Map<String, List<String>> missing = new TreeMap<>();
        int total = 0;
        for (Block block : BuiltInRegistries.BLOCK) {
            if (!looksGrowable(block) || ClimateBands.bandFor(block) != null) {
                continue;
            }
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            missing.computeIfAbsent(id.getNamespace(), k -> new ArrayList<>()).add(id.getPath());
            total++;
        }

        int bands = ClimateBands.blockBands().size();
        source.sendSuccess(() -> key("audit.summary", bands, missingCount(missing)).withStyle(ChatFormatting.GOLD), false);
        for (Map.Entry<String, List<String>> entry : missing.entrySet()) {
            List<String> names = entry.getValue();
            String shown = String.join(", ", names.subList(0, Math.min(names.size(), AUDIT_NAMES_PER_NAMESPACE)))
                    + (names.size() > AUDIT_NAMES_PER_NAMESPACE ? ", ..." : "");
            source.sendSuccess(() -> Component.literal(entry.getKey() + " (" + names.size() + "): ").withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal(shown).withStyle(ChatFormatting.GRAY)), false);
        }

        Map<ResourceLocation, String> conflicts = BiomeMoisture.conflicts();
        if (conflicts.isEmpty()) {
            source.sendSuccess(() -> key("audit.no_conflicts").withStyle(ChatFormatting.GREEN), false);
        } else {
            source.sendSuccess(() -> key("audit.conflicts", conflicts.size()).withStyle(ChatFormatting.GOLD), false);
            conflicts.forEach((biome, description) -> source.sendSuccess(() -> Component.literal(biome.toString())
                    .withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal(" " + description).withStyle(ChatFormatting.GRAY)), false));
        }
        return total;
    }

    private static int missingCount(Map<String, List<String>> missing) {
        return missing.values().stream().mapToInt(List::size).sum();
    }

    /** Staged crops, saplings and bone-mealable bush plants - what a pack author would expect to have a band. */
    private static boolean looksGrowable(Block block) {
        return block instanceof CropBlock
                || block instanceof StemBlock
                || block instanceof SaplingBlock
                || block instanceof SweetBerryBushBlock
                || block instanceof CocoaBlock
                || block instanceof NetherWartBlock
                || (block instanceof BonemealableBlock && block instanceof BushBlock);
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        GreenhouseRegistry greenhouses = Greenhouses.get(level);

        ClimateReport report = new ClimateReport(key("status.title"));
        report.row(key("status.growth", onOff(!CropGrowHandlers.isDisabled()), ClimateReport.value(CropGrowHandlers.errorCount()),
                onOff(CropGrowHandlers.isSpeedupAvailable())).withStyle(ChatFormatting.GRAY));
        report.row(key("status.cold_sweat", onOff(ClimateSampler.isColdSweatAvailable()), onOff(Greenhouses.enabled()))
                .withStyle(ChatFormatting.GRAY));
        report.row(key("status.data", ClimateReport.value(ClimateBands.blockBands().size()),
                ClimateReport.value(ClimateBands.itemBands().size())).withStyle(ChatFormatting.GRAY));
        report.row(key("status.cache", ClimateReport.value(ClimateSampler.cacheSize())).withStyle(ChatFormatting.GRAY));
        report.divider();
        report.row(key("status.greenhouses", ClimateReport.value(level.dimension().location()),
                ClimateReport.value(greenhouses.roomCount()), ClimateReport.value(greenhouses.hygrometerCount()))
                .withStyle(ChatFormatting.GRAY));
        report.row(key("status.greenhouse_cells", ClimateReport.value(greenhouses.indexedCells()),
                ClimateReport.value(greenhouses.queueLength())).withStyle(ChatFormatting.GRAY));
        report.send(line -> source.sendSuccess(() -> line, false));
        return 1;
    }

    private static MutableComponent onOff(boolean on) {
        return key(on ? "on" : "off").withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED);
    }
}
