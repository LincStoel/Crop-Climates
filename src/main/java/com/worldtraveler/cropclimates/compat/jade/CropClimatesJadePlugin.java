package com.worldtraveler.cropclimates.compat.jade;

import com.worldtraveler.cropclimates.CropClimates;
import com.worldtraveler.cropclimates.CropClimatesConfig;
import com.worldtraveler.cropclimates.entity.HygrometerEntity;
import com.worldtraveler.cropclimates.greenhouse.GreenhouseStatus;
import com.worldtraveler.cropclimates.growth.GrowthGovernor;
import com.worldtraveler.cropclimates.net.SyncedBands;
import com.worldtraveler.cropclimates.report.ClimateReport;
import com.worldtraveler.cropclimates.report.Verdict;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.GrowingPlantBodyBlock;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.EntityAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IEntityComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;
import snownee.jade.api.config.IPluginConfig;

/**
 * Optional Jade support, loaded only by Jade's own plugin discovery.
 * <ul>
 *   <li>Governed plants show only their verdict word - thriving, struggling,
 *       and so on, as named in the server config, after its configurable prefix. The numbers stay the Soil
 *       Tester's job.</li>
 *   <li>Hygrometers show the humidity they read, and whether that is a
 *       greenhouse's or the outdoor air's.</li>
 * </ul>
 */
@WailaPlugin
public final class CropClimatesJadePlugin implements IWailaPlugin {

    static final ResourceLocation CROP_VERDICT = ResourceLocation.fromNamespaceAndPath(CropClimates.MOD_ID, "crop_verdict");
    static final ResourceLocation HYGROMETER = ResourceLocation.fromNamespaceAndPath(CropClimates.MOD_ID, "hygrometer");
    private static final String VERDICT_KEY = "crop_climates_verdict";
    private static final String VERDICT_NAME_KEY = "crop_climates_verdict_name";
    private static final String VERDICT_PREFIX_KEY = "crop_climates_verdict_prefix";
    private static final String HUMIDITY_KEY = "crop_climates_humidity";
    private static final String STATUS_KEY = "crop_climates_status";

    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(CropVerdict.INSTANCE, Block.class);
        registration.registerEntityDataProvider(Hygrometer.INSTANCE, HygrometerEntity.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(CropVerdict.INSTANCE, Block.class);
        registration.registerEntityComponent(Hygrometer.INSTANCE, HygrometerEntity.class);
    }

    enum CropVerdict implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
        INSTANCE;

        @Override
        public void appendServerData(CompoundTag data, BlockAccessor accessor) {
            // Score the growing end, like the Soil Tester, so every block of
            // a tall stalk shows the verdict that actually drives its growth.
            Level level = accessor.getLevel();
            BlockPos pos = GrowthGovernor.growingEnd(level, accessor.getPosition());
            GrowthGovernor.GrowthReading reading = GrowthGovernor.read(level, pos, level.getBlockState(pos));
            if (reading != null) {
                Verdict verdict = Verdict.of(reading.total(), CropClimatesConfig.GROWTH_MAX.get());
                data.putInt(VERDICT_KEY, verdict.ordinal());
                data.putString(VERDICT_NAME_KEY, verdict.labelText());
                data.putString(VERDICT_PREFIX_KEY, CropClimatesConfig.JADE_VERDICT_PREFIX.get());
            }
        }

        /**
         * Registered for every block, so without this Jade asks the server about
         * each block the player looks at. Only banded plants - and the body of a
         * vine or kelp stalk, which reads its head - can have a verdict.
         */
        @Override
        public boolean shouldRequestData(BlockAccessor accessor) {
            Block block = accessor.getBlock();
            return SyncedBands.has(block) || block instanceof GrowingPlantBodyBlock;
        }

        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            CompoundTag data = accessor.getServerData();
            if (data.contains(VERDICT_KEY)) {
                tooltip.add(Component.literal(data.getString(VERDICT_PREFIX_KEY)).withStyle(ChatFormatting.GRAY)
                        .append(Verdict.styled(data.getInt(VERDICT_KEY), data.getString(VERDICT_NAME_KEY))));
            }
        }

        @Override
        public ResourceLocation getUid() {
            return CROP_VERDICT;
        }
    }

    /**
     * The hygrometer's reading, as its entity last synced it (refreshed once a
     * second); the server copy wins when Jade's server data has arrived.
     */
    enum Hygrometer implements IEntityComponentProvider, IServerDataProvider<EntityAccessor> {
        INSTANCE;

        @Override
        public void appendServerData(CompoundTag data, EntityAccessor accessor) {
            if (accessor.getEntity() instanceof HygrometerEntity hygrometer) {
                data.putFloat(HUMIDITY_KEY, hygrometer.humidity());
                data.putInt(STATUS_KEY, hygrometer.status().ordinal());
            }
        }

        @Override
        public void appendTooltip(ITooltip tooltip, EntityAccessor accessor, IPluginConfig config) {
            if (!(accessor.getEntity() instanceof HygrometerEntity hygrometer)) {
                return;
            }
            CompoundTag data = accessor.getServerData();
            boolean fromServer = data.contains(HUMIDITY_KEY);
            float humidity = fromServer ? data.getFloat(HUMIDITY_KEY) : hygrometer.humidity();
            GreenhouseStatus status = fromServer ? GreenhouseStatus.byId(data.getInt(STATUS_KEY)) : hygrometer.status();
            Component where = status == GreenhouseStatus.GREENHOUSE
                    ? Component.translatable("jade.crop_climates.greenhouse").withStyle(ChatFormatting.GREEN)
                    : Component.translatable("jade.crop_climates.outdoor").withStyle(ChatFormatting.GRAY);
            tooltip.add(Component.translatable("jade.crop_climates.humidity", ClimateReport.pct(humidity), where)
                    .withStyle(ChatFormatting.GRAY));
        }

        @Override
        public ResourceLocation getUid() {
            return HYGROMETER;
        }
    }
}
