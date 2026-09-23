package com.worldtraveler.cropclimates.greenhouse;

import com.worldtraveler.cropclimates.CropClimatesConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.level.LevelEvent;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Static entry points into each dimension's {@link GreenhouseRegistry}. The
 * registries of loaded dimensions are kept here so the block-change hook
 * costs one map lookup rather than a saved-data fetch.
 */
public final class Greenhouses {

    private static final Map<ResourceKey<Level>, GreenhouseRegistry> ACTIVE = new ConcurrentHashMap<>();

    private Greenhouses() {
    }

    public static GreenhouseRegistry get(ServerLevel level) {
        GreenhouseRegistry registry = ACTIVE.get(level.dimension());
        if (registry == null) {
            registry = level.getDataStorage().computeIfAbsent(GreenhouseRegistry.FACTORY, GreenhouseRegistry.NAME);
            ACTIVE.put(level.dimension(), registry);
        }
        return registry;
    }

    public static boolean enabled() {
        return CropClimatesConfig.GREENHOUSE_ENABLED.get() && WorldCells.isAvailable();
    }

    /** The greenhouse a plant at {@code pos} is in, or {@code null}. */
    @Nullable
    public static Room roomAt(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel) || !enabled()) {
            return null;
        }
        return get(serverLevel).roomAt(pos);
    }

    /**
     * From {@code LevelChunkMixin}, for every block that actually changed - in
     * the middle of {@code LevelChunk.setBlockState}, so it must never throw.
     */
    public static void onBlockChanged(Level level, BlockPos pos, BlockState oldState, BlockState newState) {
        if (!(level instanceof ServerLevel serverLevel) || !serverLevel.getServer().isSameThread()) {
            return;
        }
        GreenhouseRegistry registry = ACTIVE.get(serverLevel.dimension());
        if (registry != null && WorldCells.isAvailable()) {
            try {
                registry.onBlockChanged(pos, oldState, newState, serverLevel.getGameTime());
            } catch (RuntimeException ex) {
                WorldCells.fail(ex);
            }
        }
    }

    public static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) {
            get(level);
        }
    }

    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            ACTIVE.remove(level.dimension());
        }
    }

    public static void clear() {
        ACTIVE.clear();
    }
}
