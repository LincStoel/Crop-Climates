package com.worldtraveler.cropclimates.compat.jade;

import com.worldtraveler.cropclimates.CropClimates;
import com.worldtraveler.cropclimates.CropClimatesConfig;
import com.worldtraveler.cropclimates.entity.HygrometerEntity;
import com.worldtraveler.cropclimates.greenhouse.GreenhouseStatus;
import com.worldtraveler.cropclimates.growth.GrowthGovernor;
import com.worldtraveler.cropclimates.report.ClimateReport;
import com.worldtraveler.cropclimates.report.Verdict;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
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
 *       and so on. The numbers stay the Soil Tester's job.</li>
 *   <li>Hygrometers show the humidity they read, and whether that is a
 *       greenhouse's or the outdoor air's.</li>
 * </ul>
 */
@WailaPlugin
public final class CropClimatesJadePlugin implements IWailaPlugin {

    static final ResourceLocation CROP_VERDICT = ResourceLocation.fromNamespaceAndPath(CropClimates.MOD_ID, "crop_verdict");
    static final ResourceLocation HYGROMETER = ResourceLocation.fromNamespaceAndPath(CropClimates.MOD_ID, "hygrometer");
    private static final String VERDICT_KEY = "crop_climates_verdict";

    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(CropVerdict.INSTANCE, Block.class);
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
            GrowthGovernor.GrowthReading reading =
                    GrowthGovernor.read(accessor.getLevel(), accessor.getPosition(), accessor.getBlockState());
            if (reading != null) {
                data.putInt(VERDICT_KEY, Verdict.of(reading.total(), CropClimatesConfig.GROWTH_MAX.get()).ordinal());
            }
        }

        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            CompoundTag data = accessor.getServerData();
            if (data.contains(VERDICT_KEY)) {
                tooltip.add(Verdict.byId(data.getInt(VERDICT_KEY)).label());
            }
        }

        @Override
        public ResourceLocation getUid() {
            return CROP_VERDICT;
        }
    }

    enum Hygrometer implements IEntityComponentProvider {
        INSTANCE;

        @Override
        public void appendTooltip(ITooltip tooltip, EntityAccessor accessor, IPluginConfig config) {
            if (!(accessor.getEntity() instanceof HygrometerEntity hygrometer)) {
                return;
            }
            GreenhouseStatus status = hygrometer.status();
            Component where = status == GreenhouseStatus.GREENHOUSE
                    ? Component.translatable("jade.crop_climates.greenhouse").withStyle(ChatFormatting.GREEN)
                    : Component.translatable("jade.crop_climates.outdoor").withStyle(ChatFormatting.GRAY);
            tooltip.add(Component.translatable("jade.crop_climates.humidity", ClimateReport.pct(hygrometer.humidity()), where)
                    .withStyle(ChatFormatting.GRAY));
        }

        @Override
        public ResourceLocation getUid() {
            return HYGROMETER;
        }
    }
}
