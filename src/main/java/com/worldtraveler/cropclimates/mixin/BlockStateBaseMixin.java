package com.worldtraveler.cropclimates.mixin;

import com.worldtraveler.cropclimates.climate.ClimateBands;
import com.worldtraveler.cropclimates.growth.CropGrowHandlers;
import com.worldtraveler.cropclimates.growth.GrowthGovernor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code randomTick} has no NeoForge event - {@code SaplingBlock} never fires
 * {@code CropGrowEvent} and {@code BlockGrowFeatureEvent} only covers nether
 * fungi/huge mushrooms - so this governs the sapling/own-tick plants directly
 * at the head of {@code randomTick}.
 *
 * <p>Only blocks in {@link ClimateBands#isRandomTickGoverned} pay anything -
 * one hash-set probe - everything else falls straight through to vanilla.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateBaseMixin {

    @Shadow
    public abstract Block getBlock();

    @Inject(method = "randomTick", at = @At("HEAD"), cancellable = true)
    private void cropClimates$randomTick(ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        if (CropGrowHandlers.isForcing() || CropGrowHandlers.isDisabled()) {
            return;
        }
        Block block = this.getBlock();
        if (!ClimateBands.isRandomTickGoverned(block)) {
            return;
        }

        try {
            BlockState state = (BlockState) (Object) this;
            GrowthGovernor.GrowthReading reading = GrowthGovernor.read(level, pos, state, false);
            if (reading == null) {
                return;
            }
            double total = reading.total();

            if (total < 1.0) {
                if (random.nextDouble() >= total) {
                    ci.cancel();
                }
                return;
            }
            if (total > 1.0 && random.nextDouble() < total - 1.0) {
                CropGrowHandlers.extraTick(level, pos);
            }
        } catch (RuntimeException ex) {
            CropGrowHandlers.fail("BlockEvents.randomTick", ex);
        }
    }
}
