package com.worldtraveler.cropclimates.stress;

import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.worldtraveler.cropclimates.CropClimatesConfig;
import com.worldtraveler.cropclimates.climate.ClimateSampler;
import com.worldtraveler.cropclimates.entity.HygrometerEntity;
import com.worldtraveler.cropclimates.greenhouse.GreenhouseRegistry;
import com.worldtraveler.cropclimates.greenhouse.GreenhouseStatus;
import com.worldtraveler.cropclimates.greenhouse.Greenhouses;
import com.worldtraveler.cropclimates.greenhouse.Room;
import com.worldtraveler.cropclimates.greenhouse.RoomScan;
import com.worldtraveler.cropclimates.stress.mixin.GreenhouseRegistryAccessor;
import com.worldtraveler.cropclimates.stress.net.StressNet;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.network.PacketDistributor;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;

/**
 * {@code /ccstress} - the harness's whole interface, meant to be driven over
 * RCON by {@code tools/stress/scenarios.py}. Every command answers with one
 * compact line; bulky results go to {@code stress-results/}.
 */
public final class StressCommands {

    private StressCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("ccstress").requires(s -> s.hasPermission(2));

        root.then(Commands.literal("ticket")
                .then(Commands.literal("add").then(rect(ctx -> reply(ctx, "forced " + ForcedTicks.add(ctx.getSource().getLevel(),
                        getInteger(ctx, "x1"), getInteger(ctx, "z1"), getInteger(ctx, "x2"), getInteger(ctx, "z2")) + " chunks"))))
                .then(Commands.literal("remove").then(rect(ctx -> reply(ctx, "released " + ForcedTicks.remove(ctx.getSource().getLevel(),
                        getInteger(ctx, "x1"), getInteger(ctx, "z1"), getInteger(ctx, "x2"), getInteger(ctx, "z2")) + " chunks")))));

        root.then(Commands.literal("build")
                .then(Commands.literal("field").then(label(xyz(Commands.argument("w", integer(1)).then(Commands.argument("d", integer(1))
                        .then(Commands.argument("types", word()).then(Commands.argument("age", integer(-1))
                                .then(Commands.argument("clear", integer(0)).executes(StressCommands::buildField)))))))))
                .then(Commands.literal("greenhouse").then(label(xyz(Commands.argument("ix", integer(1)).then(Commands.argument("iz", integer(1))
                        .then(Commands.argument("iy", integer(2)).then(Commands.argument("types", word())
                                .then(Commands.argument("age", integer(-1)).executes(StressCommands::buildGreenhouse)))))))))
                .then(Commands.literal("grid").then(label(xyz(Commands.argument("nx", integer(1)).then(Commands.argument("nz", integer(1))
                        .then(Commands.argument("ix", integer(1)).then(Commands.argument("iz", integer(1)).then(Commands.argument("iy", integer(2))
                                .then(Commands.argument("gap", integer(-1)).then(Commands.argument("types", word())
                                        .then(Commands.argument("age", integer(-1)).executes(StressCommands::buildGrid))))))))))))
                .then(Commands.literal("tiers").then(label(xyz(Commands.argument("size", integer(4)).then(Commands.argument("tiers", integer(1))
                        .then(Commands.argument("spacing", integer(3)).then(Commands.argument("types", word())
                                .then(Commands.argument("age", integer(-1)).executes(StressCommands::buildTiers)))))))))
                .then(Commands.literal("cave").then(xyz(Commands.argument("sx", integer(1)).then(Commands.argument("sy", integer(1))
                        .then(Commands.argument("sz", integer(1)).executes(ctx -> {
                            UUID id = Builders.cave(ctx.getSource().getLevel(), getInteger(ctx, "x"), getInteger(ctx, "y"),
                                    getInteger(ctx, "z"), getInteger(ctx, "sx"), getInteger(ctx, "sy"), getInteger(ctx, "sz"));
                            return reply(ctx, "cave hygrometer " + id);
                        }))))))
                .then(Commands.literal("nether").then(Commands.argument("x", integer()).then(Commands.argument("z", integer())
                        .then(Commands.argument("count", integer(1)).then(Commands.argument("spacing", integer(1)).executes(ctx -> {
                            int placed = 0;
                            for (int i = 0; i < getInteger(ctx, "count"); i++) {
                                if (Builders.netherHygrometer(ctx.getSource().getLevel(),
                                        getInteger(ctx, "x") + i * getInteger(ctx, "spacing"), getInteger(ctx, "z")) != null) {
                                    placed++;
                                }
                            }
                            return reply(ctx, "placed " + placed + " nether hygrometers");
                        }))))))
                .then(Commands.literal("clear").then(xyz2(ctx -> {
                    Builders.clear(ctx.getSource().getLevel(), getInteger(ctx, "x1"), getInteger(ctx, "y1"), getInteger(ctx, "z1"),
                            getInteger(ctx, "x2"), getInteger(ctx, "y2"), getInteger(ctx, "z2"));
                    return reply(ctx, "cleared");
                }))));

        root.then(Commands.literal("roofs").then(Commands.argument("label", word())
                .then(Commands.literal("open").executes(ctx -> reply(ctx, "opened " + Builders.roofs(ctx.getSource().getLevel(), getString(ctx, "label"), true))))
                .then(Commands.literal("close").executes(ctx -> reply(ctx, "closed " + Builders.roofs(ctx.getSource().getLevel(), getString(ctx, "label"), false))))));

        root.then(Commands.literal("latency")
                .then(Commands.literal("result").executes(ctx -> reply(ctx, Driver.latencyRunning() ? "running"
                        : Driver.lastLatency == null ? "none" : Results.compact(Driver.lastLatency))))
                .then(Commands.argument("label", word()).then(Commands.argument("index", integer(0)).executes(ctx -> {
                    List<Builders.Greenhouse> list = Builders.BUILT.get(getString(ctx, "label"));
                    Builders.Greenhouse g = list.get(getInteger(ctx, "index"));
                    return reply(ctx, Driver.startLatency(ctx.getSource().getLevel(), g.hygrometer(), g.roofCentre()));
                }))));

        root.then(Commands.literal("churn")
                .then(Commands.literal("stop").executes(ctx -> reply(ctx, "stopped " + Driver.stopChurn())))
                .then(Commands.literal("room").then(Commands.argument("mode", word()).then(Commands.argument("label", word())
                        .then(Commands.argument("perTick", integer(1)).executes(ctx -> {
                            Driver.startChurn(new Driver.Churn(getString(ctx, "mode"), ctx.getSource().getLevel(),
                                    Builders.BUILT.get(getString(ctx, "label")), BlockPos.ZERO, BlockPos.ZERO,
                                    getInteger(ctx, "perTick"), RandomSource.create(42)));
                            return reply(ctx, "churning");
                        })))))
                .then(Commands.literal("far").then(Commands.argument("perTick", integer(1)).then(xyz2(ctx -> {
                    Driver.startChurn(new Driver.Churn("far", ctx.getSource().getLevel(), List.of(),
                            new BlockPos(getInteger(ctx, "x1"), getInteger(ctx, "y1"), getInteger(ctx, "z1")),
                            new BlockPos(getInteger(ctx, "x2"), getInteger(ctx, "y2"), getInteger(ctx, "z2")),
                            getInteger(ctx, "perTick"), RandomSource.create(42)));
                    return reply(ctx, "churning");
                })))));

        root.then(Commands.literal("mspt")
                .then(Commands.literal("start").then(Commands.argument("label", word()).executes(ctx -> {
                    TickTimer.begin(getString(ctx, "label"));
                    return reply(ctx, "recording");
                })))
                .then(Commands.literal("stop").executes(ctx -> {
                    JsonObject stats = TickTimer.end();
                    stats.add("state", state(ctx.getSource().getServer().getAllLevels()));
                    Results.writeJson("mspt-" + TickTimer.label(), stats);
                    return reply(ctx, Results.compact(stats));
                })));

        root.then(Commands.literal("growth")
                .then(Commands.literal("track").then(Commands.argument("label", word()).then(xyz2(ctx -> reply(ctx, "tracking "
                        + GrowthTracker.track(getString(ctx, "label"), ctx.getSource().getLevel(),
                        new BlockPos(getInteger(ctx, "x1"), getInteger(ctx, "y1"), getInteger(ctx, "z1")),
                        new BlockPos(getInteger(ctx, "x2"), getInteger(ctx, "y2"), getInteger(ctx, "z2"))))))))
                .then(Commands.literal("report").then(Commands.argument("label", word()).executes(ctx ->
                        reply(ctx, Results.compact(GrowthTracker.report(getString(ctx, "label"))))))));

        root.then(Commands.literal("stats")
                .executes(ctx -> {
                    JsonObject o = state(ctx.getSource().getServer().getAllLevels());
                    o.add("counters", Counters.toJson());
                    return reply(ctx, Results.compact(o));
                })
                .then(Commands.literal("reset").executes(ctx -> {
                    Counters.reset();
                    return reply(ctx, "reset");
                })));

        root.then(Commands.literal("status").then(Commands.argument("label", word()).executes(ctx -> {
            ServerLevel level = ctx.getSource().getLevel();
            GreenhouseRegistry reg = Greenhouses.get(level);
            com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
            for (Builders.Greenhouse g : Builders.BUILT.getOrDefault(getString(ctx, "label"), List.of())) {
                JsonObject o = new JsonObject();
                o.addProperty("hygrometer", String.valueOf(g.hygrometer()));
                o.addProperty("status", reg.statusOf(g.hygrometer()).name());
                Room room = reg.roomOf(g.hygrometer());
                if (room != null) {
                    o.addProperty("room", System.identityHashCode(room));
                    o.addProperty("size", room.size());
                    o.addProperty("members", room.memberCount());
                    o.addProperty("humidity", room.humidity(level));
                    o.addProperty("base", room.baseHumidity(level));
                }
                arr.add(o);
            }
            return reply(ctx, Results.compact(arr));
        })));

        root.then(Commands.literal("hang").then(Commands.argument("label", word()).then(xyz(Commands.argument("facing", word())
                .executes(ctx -> {
                    UUID id = Builders.hang(ctx.getSource().getLevel(), new BlockPos(getInteger(ctx, "x"), getInteger(ctx, "y"),
                            getInteger(ctx, "z")), net.minecraft.core.Direction.byName(getString(ctx, "facing")));
                    Builders.BUILT.computeIfAbsent(getString(ctx, "label"), k -> new java.util.ArrayList<>())
                            .add(new Builders.Greenhouse(0, 0, 0, 0, 0, 0, id));
                    Builders.persist();
                    return reply(ctx, String.valueOf(id));
                })))));

        root.then(Commands.literal("config").then(Commands.argument("key", word()).then(Commands.argument("value", word())
                .executes(ctx -> reply(ctx, setConfig(getString(ctx, "key"), getString(ctx, "value")))))));

        root.then(Commands.literal("instruct").then(Commands.argument("player", word()).then(Commands.argument("action", word())
                .then(Commands.argument("arg", StringArgumentType.greedyString()).executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getServer().getPlayerList().getPlayerByName(getString(ctx, "player"));
                    if (player == null) {
                        return reply(ctx, "no such player");
                    }
                    PacketDistributor.sendToPlayer(player, new StressNet.Instruct(getString(ctx, "action"), getString(ctx, "arg")));
                    return reply(ctx, "sent");
                })))));

        root.then(Commands.literal("fly").then(Commands.argument("player", word()).executes(ctx -> {
            ServerPlayer player = ctx.getSource().getServer().getPlayerList().getPlayerByName(getString(ctx, "player"));
            if (player == null) {
                return reply(ctx, "no such player");
            }
            player.getAbilities().mayfly = true;
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
            return reply(ctx, "flying");
        })));

        root.then(Commands.literal("hygrometers").then(Commands.literal("kill").executes(ctx -> {
            int n = 0;
            for (ServerLevel level : ctx.getSource().getServer().getAllLevels()) {
                for (HygrometerEntity e : level.getEntities(EntityTypeTest.forClass(HygrometerEntity.class), e -> true)) {
                    e.remove(Entity.RemovalReason.KILLED);
                    n++;
                }
            }
            return reply(ctx, "killed " + n);
        })));

        dispatcher.register(root);
    }

    // ---------------------------------------------------------------- builders

    private static int buildField(CommandContext<CommandSourceStack> ctx) {
        long t = System.nanoTime();
        int n = Builders.field(ctx.getSource().getLevel(), getInteger(ctx, "x"), getInteger(ctx, "y"), getInteger(ctx, "z"),
                getInteger(ctx, "w"), getInteger(ctx, "d"), types(ctx), getInteger(ctx, "age"), getInteger(ctx, "clear"),
                getString(ctx, "label").hashCode());
        Builders.persist();
        return reply(ctx, "field " + n + " cells in " + ms(t) + " ms");
    }

    private static int buildGreenhouse(CommandContext<CommandSourceStack> ctx) {
        long t = System.nanoTime();
        Builders.Greenhouse g = Builders.greenhouse(ctx.getSource().getLevel(), getString(ctx, "label"),
                getInteger(ctx, "x"), getInteger(ctx, "y"), getInteger(ctx, "z"), getInteger(ctx, "ix"), getInteger(ctx, "iz"),
                getInteger(ctx, "iy"), types(ctx), getInteger(ctx, "age"), Builders.GLASS, true, getString(ctx, "label").hashCode());
        Builders.persist();
        return reply(ctx, "greenhouse " + g.hygrometer() + " in " + ms(t) + " ms");
    }

    private static int buildGrid(CommandContext<CommandSourceStack> ctx) {
        long t = System.nanoTime();
        int n = Builders.grid(ctx.getSource().getLevel(), getString(ctx, "label"), getInteger(ctx, "x"), getInteger(ctx, "y"),
                getInteger(ctx, "z"), getInteger(ctx, "nx"), getInteger(ctx, "nz"), getInteger(ctx, "ix"), getInteger(ctx, "iz"),
                getInteger(ctx, "iy"), getInteger(ctx, "gap"), types(ctx), getInteger(ctx, "age"), getString(ctx, "label").hashCode());
        Builders.persist();
        return reply(ctx, "grid " + n + " greenhouses in " + ms(t) + " ms");
    }

    private static int buildTiers(CommandContext<CommandSourceStack> ctx) {
        long t = System.nanoTime();
        Builders.Greenhouse g = Builders.tiers(ctx.getSource().getLevel(), getString(ctx, "label"), getInteger(ctx, "x"),
                getInteger(ctx, "y"), getInteger(ctx, "z"), getInteger(ctx, "size"), getInteger(ctx, "tiers"),
                getInteger(ctx, "spacing"), types(ctx), getInteger(ctx, "age"), getString(ctx, "label").hashCode());
        Builders.persist();
        return reply(ctx, "tiers " + g.hygrometer() + " in " + ms(t) + " ms");
    }

    private static List<PlantType> types(CommandContext<CommandSourceStack> ctx) {
        return PlantType.parse(getString(ctx, "types").replace('+', ','));
    }

    // ------------------------------------------------------------------- state

    /** Registry state per dimension, with a status histogram and room sizes. */
    static JsonObject state(Iterable<ServerLevel> levels) {
        JsonObject out = new JsonObject();
        out.addProperty("tempCache", ClimateSampler.cacheSize());
        out.addProperty("lastTickMs", TickTimer.lastTickNanos / 1e6);
        for (ServerLevel level : levels) {
            GreenhouseRegistry reg = Greenhouses.get(level);
            if (reg.hygrometerCount() == 0 && reg.roomCount() == 0) {
                continue;
            }
            GreenhouseRegistryAccessor acc = (GreenhouseRegistryAccessor) (Object) reg;
            JsonObject o = new JsonObject();
            o.addProperty("hygrometers", reg.hygrometerCount());
            o.addProperty("rooms", reg.roomCount());
            o.addProperty("indexedCells", reg.indexedCells());
            o.addProperty("queue", reg.queueLength());
            RoomScan active = acc.cc$activeScan();
            o.addProperty("activeScanVisited", active == null ? -1 : active.visited());
            Map<GreenhouseStatus, Integer> hist = new EnumMap<>(GreenhouseStatus.class);
            for (Object key : acc.cc$probes().keySet()) {
                hist.merge(reg.statusOf((UUID) key), 1, Integer::sum);
            }
            JsonObject h = new JsonObject();
            hist.forEach((k, v) -> h.addProperty(k.name(), v));
            o.add("status", h);
            long maxRoom = 0;
            for (Room room : acc.cc$rooms().values()) {
                maxRoom = Math.max(maxRoom, room.size());
            }
            o.addProperty("largestRoom", maxRoom);
            o.addProperty("loadedHygrometerEntities",
                    level.getEntities(EntityTypeTest.forClass(HygrometerEntity.class), e -> true).size());
            out.add(level.dimension().location().toString(), o);
        }
        return out;
    }

    // ------------------------------------------------------------------ config

    /** Sets a crop_climates server config value by its TOML key, in memory and on disk. */
    private static String setConfig(String key, String raw) {
        try {
            for (Field f : CropClimatesConfig.class.getFields()) {
                if (!Modifier.isStatic(f.getModifiers()) || !ModConfigSpec.ConfigValue.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                ModConfigSpec.ConfigValue<Object> value = (ModConfigSpec.ConfigValue<Object>) f.get(null);
                List<String> path = value.getPath();
                if (!path.get(path.size() - 1).equals(key)) {
                    continue;
                }
                Object current = value.get();
                Object parsed = current instanceof Integer ? Integer.valueOf(raw)
                        : current instanceof Double ? Double.valueOf(raw)
                        : current instanceof Boolean ? Boolean.valueOf(raw)
                        : raw;
                value.set(parsed);
                value.save();
                return key + " = " + value.get();
            }
            return "unknown key " + key;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return "failed: " + ex;
        }
    }

    // ----------------------------------------------------------------- helpers

    private static ArgumentBuilder<CommandSourceStack, ?> label(ArgumentBuilder<CommandSourceStack, ?> next) {
        return Commands.argument("label", word()).then(next);
    }

    private static ArgumentBuilder<CommandSourceStack, ?> xyz(ArgumentBuilder<CommandSourceStack, ?> next) {
        return Commands.argument("x", integer()).then(Commands.argument("y", integer()).then(Commands.argument("z", integer()).then(next)));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> rect(com.mojang.brigadier.Command<CommandSourceStack> cmd) {
        return Commands.argument("x1", integer()).then(Commands.argument("z1", integer()).then(Commands.argument("x2", integer())
                .then(Commands.argument("z2", integer()).executes(cmd))));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> xyz2(com.mojang.brigadier.Command<CommandSourceStack> cmd) {
        return Commands.argument("x1", integer()).then(Commands.argument("y1", integer()).then(Commands.argument("z1", integer())
                .then(Commands.argument("x2", integer()).then(Commands.argument("y2", integer()).then(Commands.argument("z2", integer())
                        .executes(cmd))))));
    }

    private static int reply(CommandContext<CommandSourceStack> ctx, String message) {
        ctx.getSource().sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static long ms(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
