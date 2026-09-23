package com.worldtraveler.cropclimates.advancement;

import com.mojang.logging.LogUtils;
import com.worldtraveler.cropclimates.CropClimates;
import com.worldtraveler.cropclimates.climate.ClimateBands;
import com.worldtraveler.cropclimates.climate.ClimateSampler;
import com.worldtraveler.cropclimates.growth.CropGrowHandlers;
import com.worldtraveler.cropclimates.growth.GrowthGovernor;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.slf4j.Logger;

/**
 * The mod's advancements, in the Husbandry tab. Each has one
 * {@code minecraft:impossible} criterion and is granted from code where its
 * moment happens: {@link #GREENHOUSE} in the greenhouse registry,
 * {@link #SOILED_IT} and {@link #INVASIVE} by the Soil Tester, and
 * {@link #THRIVING} and {@link #DESPERATE} here, when a player plants.
 */
public final class CropAdvancements {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** "Hygrometer? I hardly know her!" - a hygrometer a player hung sealed a new greenhouse. */
    public static final ResourceLocation GREENHOUSE = id("greenhouse");
    /** "Soiled it!" - the Soil Tester used on a crop. */
    public static final ResourceLocation SOILED_IT = id("soiled_it");
    /** "Thriving!" - planted where it grows faster than vanilla. */
    public static final ResourceLocation THRIVING = id("thriving");
    /** "Desperate conditions" - planted where it grows at a tenth of vanilla speed or less. */
    public static final ResourceLocation DESPERATE = id("desperate_conditions");
    /** "Invasive!" - the Soil Tester used on a player or mob. */
    public static final ResourceLocation INVASIVE = id("invasive");

    private static final double THRIVING_ABOVE = 1.0;
    private static final double DESPERATE_AT_MOST = 0.1;

    private CropAdvancements() {
    }

    /** Grants an advancement's remaining criteria; missing ones (a datapack removed it) are skipped. */
    public static void award(ServerPlayer player, ResourceLocation id) {
        AdvancementHolder advancement = player.server.getAdvancements().get(id);
        if (advancement == null) {
            return;
        }
        AdvancementProgress progress = player.getAdvancements().getOrStartProgress(advancement);
        if (progress.isDone()) {
            return;
        }
        for (String criterion : progress.getRemainingCriteria()) {
            player.getAdvancements().award(advancement, criterion);
        }
    }

    /**
     * Thriving and Desperate conditions: a crop or sapling a player plants is
     * judged once, as it is placed, by the same growth multiplier its random
     * ticks will use (vanilla speed is 1.0).
     */
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockState state = event.getPlacedBlock();
        if (ClimateBands.bandFor(state.getBlock()) == null || CropGrowHandlers.isDisabled()) {
            return;
        }
        try {
            BlockPos pos = event.getPos();
            GrowthGovernor.GrowthReading reading = ClimateSampler.unbudgeted(() -> GrowthGovernor.read(level, pos, state));
            if (reading == null) {
                return;
            }
            if (reading.total() > THRIVING_ABOVE) {
                award(player, THRIVING);
            } else if (reading.total() <= DESPERATE_AT_MOST) {
                award(player, DESPERATE);
            }
        } catch (RuntimeException ex) {
            // An advancement is never worth breaking a placement over.
            LOGGER.debug("crop_climates: planting advancement check failed", ex);
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(CropClimates.MOD_ID, path);
    }
}
