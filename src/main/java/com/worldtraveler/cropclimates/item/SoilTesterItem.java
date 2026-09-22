package com.worldtraveler.cropclimates.item;

import com.worldtraveler.cropclimates.climate.TemperatureUnits;
import com.worldtraveler.cropclimates.report.ClimateReport;
import com.worldtraveler.cropclimates.report.Reports;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

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

    public SoilTesterItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (!level.isClientSide() && player != null) {
            report(level, player, context.getClickedPos());
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
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide()) {
            // No block was targeted - read the ground the player is standing on.
            report(level, player, player.blockPosition().below());
        }
        return InteractionResultHolder.success(player.getItemInHand(hand));
    }

    private static void report(Level level, Player player, BlockPos pos) {
        Reports.climate(level, pos, ClimateReport.key("soil_tester"), TemperatureUnits.forPlayer(player))
                .send(line -> player.displayClientMessage(line, false));
    }
}
