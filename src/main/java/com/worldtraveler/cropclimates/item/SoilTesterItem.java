package com.worldtraveler.cropclimates.item;

import com.worldtraveler.cropclimates.CropClimatesConfig;
import com.worldtraveler.cropclimates.climate.BiomeMoisture;
import com.worldtraveler.cropclimates.climate.ClimateBand;
import com.worldtraveler.cropclimates.climate.ClimateBands;
import com.worldtraveler.cropclimates.climate.ClimateSampler;
import com.worldtraveler.cropclimates.growth.GrowthGovernor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
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

import java.util.OptionalDouble;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Right-click a growing crop (or bare ground) for the local climate reading
 * and, if it is a governed plant, the multiplier the system is actually
 * scoring it with. Direct port of {@code soil_tester.js}'s {@code wtReport}
 * - same numbers, same verdict tiers, same bar, because it reuses
 * {@link GrowthGovernor#read} rather than reimplementing it.
 *
 * <p>Ripe crops go to RightClickHarvest first (it hooks the block-interact
 * event before item use runs) - deliberate, so the Soil Tester only ever
 * reports on a plant that is still growing.
 *
 * <p>Reading a crop always forces a fresh greenhouse recalculation (see
 * {@code explain} in {@link GrowthGovernor#read}), so a room's cached
 * enclosure values only go stale between Soil Tester checks, never during
 * one. That recalculation is the one expensive path here, so only a report
 * that actually hit it (the crop was enclosed) puts the item on a short
 * cooldown - using the Soil Tester in the open never does.
 */
public class SoilTesterItem extends Item {

    private static final int ENCLOSED_COOLDOWN_TICKS = 30;

    public SoilTesterItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (!level.isClientSide() && player != null) {
            if (report(level, player, context.getClickedPos())) {
                player.getCooldowns().addCooldown(this, ENCLOSED_COOLDOWN_TICKS);
            }
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (!player.level().isClientSide()) {
            if (target instanceof Player || target instanceof Enemy) {
                tell(player, "§6The §f" + target.getName().getString() + "§6 did not allow you to take their temperature...");
            } else {
                double tempF = 97.0 + ThreadLocalRandom.current().nextDouble() * 2.0;
                tell(player, "§6The §f" + target.getName().getString() + "§6's temperature is §f"
                        + String.format(Locale.ROOT, "%.1f", tempF) + "°F");
            }
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide()) {
            // No block was targeted - read the ground the player is standing on.
            if (report(level, player, player.blockPosition().below())) {
                player.getCooldowns().addCooldown(this, ENCLOSED_COOLDOWN_TICKS);
            }
        }
        return InteractionResultHolder.success(player.getItemInHand(hand));
    }

    private static final String BORDER = "§8" + "─".repeat(30);

    /** Returns whether the crop was in an enclosed (greenhouse) space. */
    private static boolean report(Level level, Player player, BlockPos pos) {
        var block = level.getBlockState(pos).getBlock();
        var biomeHolder = level.getBiome(pos);
        String biomeId = biomeHolder.unwrapKey().map(k -> k.location().toString()).orElse("unknown");
        double moisture = BiomeMoisture.moistureOf(biomeHolder, CropClimatesConfig.DEFAULT_BIOME_MOISTURE.get());
        OptionalDouble tempF = ClimateSampler.temperatureF(level, pos);
        boolean seesSky = level.canSeeSky(pos);

        tell(player, "§8┌─ §6Soil Tester " + BORDER);

        if (tempF.isEmpty()) {
            row(player, "§f" + biomeId);
            row(player, "§7Temperature unavailable right now; try again in a moment.");
            row(player, "§7Sky visible §f" + (seesSky ? "§aTrue" : "§cFalse"));
            tell(player, "§8└" + BORDER);
            return false;
        }

        row(player, "§f" + biomeId + "   §7Sky visible §f" + (seesSky ? "§aTrue" : "§cFalse"));
        row(player, "§7Temp §f" + Math.round(tempF.getAsDouble()) + "°F   §7Humidity §f" + pct(moisture));

        ClimateBand band = ClimateBands.bandFor(block);
        if (band == null) {
            tell(player, "§8└" + BORDER);
            return false;
        }

        GrowthGovernor.GrowthReading reading = GrowthGovernor.read(level, pos, level.getBlockState(pos), true);
        if (reading == null) {
            tell(player, "§8└" + BORDER);
            return false;
        }
        double total = reading.total();
        boolean enclosed = reading.waiver() == GrowthGovernor.GrowthReading.HumidityWaiver.ENCLOSED;
        boolean submerged = reading.waiver() == GrowthGovernor.GrowthReading.HumidityWaiver.SUBMERGED;

        tell(player, "§8├" + BORDER);
        row(player, "§f" + BuiltInRegistries.BLOCK.getKey(block));
        row(player, "§7  wants §f" + num(band.tempLo()) + "–" + num(band.tempHi()) + "°F"
                + (reading.waterTemp() ? " §8(water)" : "") + "   "
                + axisMark(reading.tempF(), band.tempLo(), band.tempHi()));

        if (submerged) {
            row(player, "§a  Submerged");
        } else {
            String humidityMark = enclosed
                    ? axisMark(reading.effectiveMoisture(), band.moistLo(), band.moistHi())
                    : axisMark(moisture, band.moistLo(), band.moistHi());
            row(player, "§7  wants §fhumidity " + pct(band.moistLo()) + "–" + pct(band.moistHi()) + "   " + humidityMark);

            if (enclosed) {
                row(player, "§aEnclosed in Greenhouse §8[humidity " + pct(reading.effectiveMoisture()) + "]");
            }
        }

        tell(player, "§8├" + BORDER);
        String[] verdict = verdict(total, CropClimatesConfig.GROWTH_MAX.get());
        row(player, "§7Growth §f" + verdict[0] + String.format(Locale.ROOT, "%.2f", total)
                + "x §8[" + bar(total, CropClimatesConfig.GROWTH_MAX.get()) + "] " + verdict[0] + verdict[1]);
        if (total < 0.85) {
            row(player, "§8Consider adjusting the climate or soil conditions.");
        }
        tell(player, "§8└" + BORDER);
        return enclosed;
    }

    private static void tell(Player player, String text) {
        player.displayClientMessage(Component.literal(text), false);
    }

    private static void row(Player player, String text) {
        player.displayClientMessage(Component.literal("§8│ " + text), false);
    }

    private static String pct(double v) {
        return Math.round(v * 100) + "%";
    }

    private static String num(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) {
            return Long.toString((long) v);
        }
        return String.valueOf(v);
    }

    private static String axisMark(double v, double lo, double hi) {
        if (v >= lo && v <= hi) {
            return "§a[ok]";
        }
        return v < lo ? "§b[too low]" : "§c[too high]";
    }

    private static String bar(double total, double growthMax) {
        int filled = (int) Math.round((Math.min(total, growthMax) / growthMax) * 10);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append(i < filled ? '|' : '.');
        }
        return sb.toString();
    }

    private static String[] verdict(double total, double growthMax) {
        if (total >= growthMax * 0.92) {
            return new String[]{"§a", "thriving"};
        }
        if (total >= 1.05) {
            return new String[]{"§a", "better than usual"};
        }
        if (total >= 0.85) {
            return new String[]{"§f", "about normal"};
        }
        if (total >= 0.5) {
            return new String[]{"§e", "sluggish"};
        }
        if (total >= 0.2) {
            return new String[]{"§6", "struggling"};
        }
        if (total >= 0.06) {
            return new String[]{"§c", "barely alive"};
        }
        return new String[]{"§4", "effectively dead"};
    }
}
