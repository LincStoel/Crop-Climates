package com.worldtraveler.cropclimates.item;

import com.worldtraveler.cropclimates.advancement.CropAdvancements;
import com.worldtraveler.cropclimates.climate.ClimateBands;
import com.worldtraveler.cropclimates.climate.TemperatureUnits;
import com.worldtraveler.cropclimates.growth.GrowthGovernor;
import com.worldtraveler.cropclimates.report.ClimateReport;
import com.worldtraveler.cropclimates.report.Reports;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Right-click a growing crop (or bare ground) for the local climate reading
 * and, if it is a governed plant, the multiplier the system is actually
 * scoring it with - see {@link Reports#climate}.
 *
 * <p>Ripe crops go to RightClickHarvest first (it hooks the block-interact
 * event before item use runs) - deliberate, so the Soil Tester only ever
 * reports on a plant that is still growing.
 */
public class SoilTesterItem extends Item {

    private static final int DEAD_BROWN = 0x8B5A2B;
    private static final int COOLDOWN_TICKS = 20;

    public SoilTesterItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (!level.isClientSide() && player != null) {
            if (player.getCooldowns().isOnCooldown(this)) {
                return InteractionResult.PASS;
            }
            BlockPos clicked = context.getClickedPos();
            report(level, player, clicked);
            player.getCooldowns().addCooldown(this, COOLDOWN_TICKS);
            BlockPos plant = GrowthGovernor.growingEnd(level, clicked);
            if (player instanceof ServerPlayer serverPlayer && ClimateBands.bandFor(level.getBlockState(plant).getBlock()) != null) {
                CropAdvancements.award(serverPlayer, CropAdvancements.SOILED_IT);
            }
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (!player.level().isClientSide()) {
            Component name = target.getName().copy().withStyle(ChatFormatting.WHITE);
            if (target instanceof Player || target instanceof Enemy) {
                player.displayClientMessage(ClimateReport.key("entity_refused", name).withStyle(ChatFormatting.GOLD), false);
            } else {
                double tempF = 97.0 + ThreadLocalRandom.current().nextDouble() * 2.0;
                player.displayClientMessage(ClimateReport.key("entity_temperature", name,
                        ClimateReport.value(TemperatureUnits.formatPrecise(tempF, TemperatureUnits.forPlayer(player))))
                        .withStyle(ChatFormatting.GOLD), false);
            }
            if (player instanceof ServerPlayer serverPlayer && (target instanceof Player || target instanceof Mob)) {
                CropAdvancements.award(serverPlayer, CropAdvancements.INVASIVE);
            }
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide()) {
            if (player.getCooldowns().isOnCooldown(this)) {
                return InteractionResultHolder.pass(player.getItemInHand(hand));
            }
            // No block was targeted - read the open cell the player is standing
            // in, not the ground: the ground block itself reads as roofed and
            // rain never falls on it.
            report(level, player, GrowthGovernor.standingCell(player));
            player.getCooldowns().addCooldown(this, COOLDOWN_TICKS);
        }
        return InteractionResultHolder.success(player.getItemInHand(hand));
    }

    private static void report(Level level, Player player, BlockPos pos) {
        if (level.getBlockState(pos).is(Blocks.DEAD_BUSH)) {
            ClimateReport report = new ClimateReport(ClimateReport.key("soil_tester"));
            report.row(ClimateReport.key("dead_bush",
                    ClimateReport.key("dead_bush.dead").withColor(DEAD_BROWN)).withStyle(ChatFormatting.GRAY));
            report.send(line -> player.displayClientMessage(line, false));
            return;
        }
        Reports.climate(level, pos, ClimateReport.key("soil_tester"), TemperatureUnits.forPlayer(player))
                .send(line -> player.displayClientMessage(line, false));
    }
}
