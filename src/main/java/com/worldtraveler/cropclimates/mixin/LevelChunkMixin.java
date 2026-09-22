package com.worldtraveler.cropclimates.mixin;

import com.worldtraveler.cropclimates.greenhouse.Greenhouses;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Greenhouse change detection. Every runtime block change - players,
 * pistons, fluids, explosions, other mods - funnels through
 * {@code LevelChunk.setBlockState}, which returns the previous state only when
 * something actually changed. NeoForge's place/break events miss most of those.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {

    @Shadow
    @Final
    Level level;

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void cropClimates$onSetBlockState(BlockPos pos, BlockState state, boolean isMoving,
                                              CallbackInfoReturnable<BlockState> cir) {
        BlockState previous = cir.getReturnValue();
        if (previous != null && !level.isClientSide()) {
            Greenhouses.onBlockChanged(level, pos, previous, state);
        }
    }
}
