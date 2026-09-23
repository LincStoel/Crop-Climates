package com.worldtraveler.cropclimates.item;

import com.worldtraveler.cropclimates.entity.HygrometerEntity;
import com.worldtraveler.cropclimates.greenhouse.Greenhouses;
import com.worldtraveler.cropclimates.report.ClimateReport;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;

import java.util.List;

/** Hangs a {@link HygrometerEntity} on a wall - placement mirrors vanilla's {@code HangingEntityItem}. */
public class HygrometerItem extends Item {

    public HygrometerItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Direction face = context.getClickedFace();
        BlockPos pos = context.getClickedPos().relative(face);
        Player player = context.getPlayer();
        ItemStack stack = context.getItemInHand();
        if (face.getAxis().isVertical() || (player != null && !player.mayUseItemAt(pos, face, stack))) {
            return InteractionResult.FAIL;
        }

        Level level = context.getLevel();
        HygrometerEntity hygrometer = new HygrometerEntity(level, pos, face);
        if (!hygrometer.survives()) {
            return InteractionResult.CONSUME;
        }
        if (level instanceof ServerLevel serverLevel) {
            hygrometer.playPlacementSound();
            level.gameEvent(player, GameEvent.ENTITY_PLACE, hygrometer.position());
            level.addFreshEntity(hygrometer);
            if (player != null) {
                // The placer hears how its first scan went: the report when it makes (or joins)
                // a greenhouse, a message when the space is too large for one.
                Greenhouses.get(serverLevel).placedBy(hygrometer.getUUID(), player.getUUID(), serverLevel.getGameTime());
            }
        }
        stack.shrink(1);
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(ClimateReport.key("hygrometer.tooltip").withStyle(ChatFormatting.GRAY));
    }
}
