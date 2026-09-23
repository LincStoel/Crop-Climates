package com.worldtraveler.cropclimates.stress;

import com.worldtraveler.cropclimates.entity.HygrometerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Scenario construction by direct {@code setBlock} (client sync, no neighbour
 * shape updates) - fast, deterministic from a seed, and free of the command
 * volume limits. Everything built is remembered by label so later commands
 * (roofs, latency, churn, growth tracking) can find it.
 */
public final class Builders {

    public static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    static final BlockState AIR = Blocks.AIR.defaultBlockState();
    static final BlockState GLASS = Blocks.GLASS.defaultBlockState();
    static final BlockState DIRT = Blocks.DIRT.defaultBlockState();

    /**
     * One built greenhouse. Interior cells are x in [x0+1, x0+ix], z in
     * [z0+1, z0+iz], y in [y, y+iy-1] (the soil layer counts - farmland
     * passes hearth air sideways); the roof is at y+iy.
     */
    public record Greenhouse(int x0, int y, int z0, int ix, int iz, int iy, @Nullable UUID hygrometer) {
        public BlockPos roofCentre() {
            return new BlockPos(x0 + 1 + ix / 2, y + iy, z0 + 1 + iz / 2);
        }

        public boolean contains(BlockPos p) {
            return p.getX() > x0 && p.getX() <= x0 + ix && p.getZ() > z0 && p.getZ() <= z0 + iz
                    && p.getY() >= y && p.getY() < y + iy;
        }
    }

    public static final Map<String, List<Greenhouse>> BUILT = new LinkedHashMap<>();

    private Builders() {
    }

    /** Saves {@link #BUILT} to {@code stress-results/built.json} so it survives a restart. */
    public static void persist() {
        com.google.gson.JsonObject root = new com.google.gson.JsonObject();
        BUILT.forEach((label, list) -> {
            com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
            for (Greenhouse g : list) {
                com.google.gson.JsonArray e = new com.google.gson.JsonArray();
                e.add(g.x0());
                e.add(g.y());
                e.add(g.z0());
                e.add(g.ix());
                e.add(g.iz());
                e.add(g.iy());
                e.add(String.valueOf(g.hygrometer()));
                arr.add(e);
            }
            root.add(label, arr);
        });
        Results.writeJson("built", root);
    }

    public static void restore() {
        java.nio.file.Path file = Results.dir().resolve("built.json");
        if (!java.nio.file.Files.exists(file)) {
            return;
        }
        try {
            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(
                    java.nio.file.Files.readString(file)).getAsJsonObject();
            BUILT.clear();
            for (var entry : root.entrySet()) {
                List<Greenhouse> list = new ArrayList<>();
                for (var el : entry.getValue().getAsJsonArray()) {
                    var a = el.getAsJsonArray();
                    String id = a.get(6).getAsString();
                    list.add(new Greenhouse(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt(), a.get(3).getAsInt(),
                            a.get(4).getAsInt(), a.get(5).getAsInt(), "null".equals(id) ? null : UUID.fromString(id)));
                }
                BUILT.put(entry.getKey(), list);
            }
            Results.log("restored " + BUILT.size() + " build labels");
        } catch (Exception ex) {
            Results.log("could not restore built.json: " + ex);
        }
    }

    static void set(ServerLevel level, BlockPos pos, BlockState state) {
        level.setBlock(pos, state, FLAGS);
    }

    // ------------------------------------------------------------------ field

    /** An open field, {@code types} in equal stripes along x. Returns the number of plant cells. */
    public static int field(ServerLevel level, int x0, int y, int z0, int w, int d, List<PlantType> types,
                            int age, int clearAbove, long seed) {
        RandomSource random = RandomSource.create(seed);
        clear(level, x0, y + 1, z0, x0 + w - 1, y + clearAbove, z0 + d - 1);
        int stripe = Math.max(1, (w + types.size() - 1) / types.size());
        BlockPos.MutableBlockPos base = new BlockPos.MutableBlockPos();
        for (int lx = 0; lx < w; lx++) {
            PlantType type = types.get(Math.min(types.size() - 1, lx / stripe));
            for (int lz = 0; lz < d; lz++) {
                set(level, base.set(x0 + lx, y - 1, z0 + lz), DIRT);
                type.place(level, x0 + lx, y, z0 + lz, lx % stripe, lz, age, random);
            }
        }
        return w * d;
    }

    // ------------------------------------------------------------- greenhouse

    /**
     * A glass box whose soil layer is at {@code y}: interior {@code ix × iz},
     * {@code iy} tall including the soil layer. The westmost interior column
     * is a bare walkway under the hygrometer, which hangs on the west wall at
     * mid-height facing east.
     */
    public static Greenhouse greenhouse(ServerLevel level, String label, int x0, int y, int z0, int ix, int iz, int iy,
                                        List<PlantType> types, int age, BlockState wall, boolean hygrometer, long seed) {
        RandomSource random = RandomSource.create(seed);
        int x1 = x0 + ix + 1, z1 = z0 + iz + 1, top = y + iy;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                set(level, p.set(x, y - 1, z), DIRT);
                boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
                for (int yy = y; yy <= top; yy++) {
                    if (edge || yy == top) {
                        set(level, p.set(x, yy, z), wall);
                    } else if (yy > y) {
                        set(level, p.set(x, yy, z), AIR);
                    }
                }
            }
        }
        int usable = Math.max(1, ix - 1);
        int stripe = Math.max(1, (usable + types.size() - 1) / types.size());
        for (int lx = 0; lx < ix; lx++) {
            for (int lz = 0; lz < iz; lz++) {
                PlantType type = lx == 0 ? PlantType.EMPTY : types.get(Math.min(types.size() - 1, (lx - 1) / stripe));
                type.place(level, x0 + 1 + lx, y, z0 + 1 + lz, (lx - 1) % stripe, lz, age, random);
            }
        }
        UUID id = null;
        if (hygrometer) {
            int hy = Math.max(1, Math.min(iy - 1, iy / 2));
            id = hang(level, new BlockPos(x0 + 1, y + hy, z0 + 1 + iz / 2), Direction.EAST);
        }
        Greenhouse g = new Greenhouse(x0, y, z0, ix, iz, iy, id);
        BUILT.computeIfAbsent(label, k -> new ArrayList<>()).add(g);
        return g;
    }

    /**
     * {@code nx × nz} greenhouses. {@code gap} blocks between neighbours; a gap
     * of -1 makes neighbours share one wall.
     */
    public static int grid(ServerLevel level, String label, int x0, int y, int z0, int nx, int nz, int ix, int iz, int iy,
                           int gap, List<PlantType> types, int age, long seed) {
        int stepX = ix + 2 + gap, stepZ = iz + 2 + gap;
        int n = 0;
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < nz; j++) {
                greenhouse(level, label, x0 + i * stepX, y, z0 + j * stepZ, ix, iz, iy, types, age, GLASS, true,
                        seed + 31L * n);
                n++;
            }
        }
        return n;
    }

    /**
     * A multi-storey greenhouse: {@code tiers} farm floors {@code spacing}
     * apart, each lit by a sea-lantern grid so every crop gets light 9+, with
     * a one-block gap along the walls so the whole building is one room.
     */
    public static Greenhouse tiers(ServerLevel level, String label, int x0, int y, int z0, int size, int tiers, int spacing,
                                   List<PlantType> types, int age, long seed) {
        int iy = tiers * spacing;
        Greenhouse g = greenhouse(level, label, x0, y, z0, size, size, iy, types, age, GLASS, true, seed);
        RandomSource random = RandomSource.create(seed ^ 0x5EED);
        BlockState lantern = Blocks.SEA_LANTERN.defaultBlockState();
        int stripe = Math.max(1, (size - 2 + types.size() - 1) / types.size());
        for (int t = 0; t < tiers; t++) {
            int fy = y + t * spacing;
            for (int lx = 1; lx < size - 1; lx++) {
                for (int lz = 1; lz < size - 1; lz++) {
                    int x = x0 + 1 + lx, z = z0 + 1 + lz;
                    if (t > 0) {
                        PlantType type = types.get(Math.min(types.size() - 1, (lx - 1) / stripe));
                        type.place(level, x, fy, z, (lx - 1) % stripe, lz, age, random);
                    }
                    if (lx % 4 == 2 && lz % 4 == 2 && !PlantType.waterRow(lz)) {
                        set(level, new BlockPos(x, fy, z), lantern);
                        set(level, new BlockPos(x, fy + 1, z), AIR);
                    }
                }
            }
        }
        return g;
    }

    // ------------------------------------------------------------ pathological

    /** Carves a sealed cavern and hangs a hygrometer on its west wall. */
    public static UUID cave(ServerLevel level, int x0, int y0, int z0, int sx, int sy, int sz) {
        clear(level, x0, y0, z0, x0 + sx - 1, y0 + sy - 1, z0 + sz - 1);
        return hang(level, new BlockPos(x0, y0 + 2, z0 + sz / 2), Direction.EAST);
    }

    /**
     * Hangs a hygrometer somewhere open in a Nether column near (x, z): an air
     * cell with a solid block to its west and mostly air around it.
     */
    @Nullable
    public static UUID netherHygrometer(ServerLevel level, int x, int z) {
        for (int attempt = 0; attempt < 40; attempt++) {
            int cx = x + attempt * 3;
            for (int y = 110; y > 34; y--) {
                BlockPos pos = new BlockPos(cx, y, z);
                if (!level.getBlockState(pos).isAir() || !level.getBlockState(pos.west()).isSolid()) {
                    continue;
                }
                int air = 0;
                for (BlockPos q : BlockPos.betweenClosed(pos.offset(0, -2, -2), pos.offset(4, 2, 2))) {
                    if (level.getBlockState(q).isAir()) {
                        air++;
                    }
                }
                if (air >= 100) { // of 125
                    return hang(level, pos, Direction.EAST);
                }
            }
        }
        return null;
    }

    /** Hangs a hygrometer at {@code pos} facing {@code facing} (the wall is behind it). */
    public static UUID hang(ServerLevel level, BlockPos pos, Direction facing) {
        if (!level.getBlockState(pos.relative(facing.getOpposite())).isSolid()) {
            set(level, pos.relative(facing.getOpposite()), GLASS);
        }
        HygrometerEntity entity = new HygrometerEntity(level, pos, facing);
        level.addFreshEntity(entity);
        return entity.getUUID();
    }

    public static void clear(ServerLevel level, int x1, int y1, int z1, int x2, int y2, int z2) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                    if (!level.getBlockState(p.set(x, y, z)).isAir()) {
                        set(level, p, AIR);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ roofs

    /** Opens (air) or closes (glass) the roof centre of every greenhouse built under {@code label}. */
    public static int roofs(ServerLevel level, String label, boolean open) {
        int n = 0;
        for (Greenhouse g : BUILT.getOrDefault(label, List.of())) {
            level.setBlock(g.roofCentre(), open ? AIR : GLASS, Block.UPDATE_ALL);
            n++;
        }
        return n;
    }
}
