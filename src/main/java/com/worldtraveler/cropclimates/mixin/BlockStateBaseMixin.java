package com.worldtraveler.cropclimates.mixin;

import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import com.worldtraveler.cropclimates.climate.ClimateBands;
import com.worldtraveler.cropclimates.growth.CropGrowHandlers;
import com.worldtraveler.cropclimates.growth.GrowthGovernor;
import com.worldtraveler.cropclimates.growth.Regression;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code randomTick} has no NeoForge event - {@code SaplingBlock} never fires
 * {@code CropGrowEvent} and {@code BlockGrowFeatureEvent} only covers nether
 * fungi/huge mushrooms - so this governs the sapling/own-tick plants directly
 * around {@code randomTick}: a slowed tick is cancelled at the head, and a
 * sped-up one gets its extra tick at the tail.
 *
 * <p>The extra tick has to come after the original one. The original tick
 * runs with the state captured before it started; had the extra tick grown
 * the plant first, the original would write that same stage again (a crop,
 * cane or pitcher sets {@code age + 1} from its stale state) and the speed-up
 * would be lost. {@link CropGrowHandlers#extraTick} re-reads the block.
 *
 * <p>A stalk that grows by stacking (bamboo, cane, cactus, chorus) moves its
 * tip up when the original tick succeeds; the block ticked is then no longer
 * the one that grows, so the extra tick goes to the new tip instead. Two-block
 * plants such as the pitcher crop keep theirs: their upper half is the same
 * block but never the part that grows.
 *
 * <p>Only blocks in {@link ClimateBands#isRandomTickGoverned} pay anything -
 * one hash-set probe - everything else falls straight through to vanilla.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateBaseMixin {

    @Shadow
    public abstract Block getBlock();

    @Inject(method = "randomTick", at = @At("HEAD"), cancellable = true)
    private void cropClimates$randomTick(ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci,
                                         @Share("cropClimates$extra") LocalBooleanRef extra,
                                         @Share("cropClimates$tip") LocalBooleanRef tip) {
        if (CropGrowHandlers.isForcing() || CropGrowHandlers.isDisabled()) {
            return;
        }
        Block block = this.getBlock();
        if (!ClimateBands.isRandomTickGoverned(block)) {
            return;
        }

        try {
            BlockState state = (BlockState) (Object) this;
            GrowthGovernor.GrowthReading reading = GrowthGovernor.read(level, pos, state);
            if (reading == null) {
                return;
            }
            double total = reading.total();

            if (total < 1.0) {
                if (random.nextDouble() >= total) {
                    ci.cancel();
                    Regression.consider(level, pos, state, reading);
                }
                return;
            }
            if (total > 1.0 && random.nextDouble() < total - 1.0) {
                extra.set(true);
                tip.set(!state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                        && !level.getBlockState(pos.above()).is(block));
            }
        } catch (RuntimeException ex) {
            CropGrowHandlers.fail("BlockEvents.randomTick", ex);
        }
    }

    @Inject(method = "randomTick", at = @At("TAIL"))
    private void cropClimates$extraTick(ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci,
                                        @Share("cropClimates$extra") LocalBooleanRef extra,
                                        @Share("cropClimates$tip") LocalBooleanRef tip) {
        if (!extra.get()) {
            return;
        }
        // The original tick stacked a new block on the tip: that block is where growth continues.
        boolean grewUp = tip.get() && level.getBlockState(pos.above()).is(this.getBlock());
        CropGrowHandlers.extraTick(level, grewUp ? pos.above() : pos);
    }
}
