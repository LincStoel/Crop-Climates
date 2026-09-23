package com.worldtraveler.cropclimates.greenhouse;

import com.mojang.logging.LogUtils;
import com.worldtraveler.cropclimates.CropClimatesConfig;
import com.worldtraveler.cropclimates.CropClimatesTags;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Every hygrometer and greenhouse in one dimension, saved with the world.
 *
 * <ul>
 *   <li><b>Lookup</b> - a cell -> room index answers "which greenhouse is this
 *       crop in" with one hash probe, so crops never scan anything.</li>
 *   <li><b>Scanning</b> - hygrometers queue for scans, and one scan at a time
 *       advances each tick within a shared {@code greenhouseCellsPerTick}
 *       budget. A room keeps its previous values until its rescan finishes.</li>
 *   <li><b>Change detection</b> - {@code LevelChunkMixin} reports every block
 *       change; a section index finds the scans whose footprint (walls
 *       included, grown by one) contains it, and those rescan after
 *       {@code greenhouseChangeDelay}. A routine rescan every
 *       {@code greenhouseRescanInterval} backs this up.</li>
 * </ul>
 *
 * <p>There is no limit on the number of greenhouses; each is capped in size by
 * {@code greenhouseMaxRadius}/{@code greenhouseMaxHeight}. Only ever touched on the server thread.
 */
public final class GreenhouseRegistry extends SavedData {

    private static final Logger LOGGER = LogUtils.getLogger();
    static final String NAME = "crop_climates_greenhouses";

    static final SavedData.Factory<GreenhouseRegistry> FACTORY =
            new SavedData.Factory<>(GreenhouseRegistry::new, GreenhouseRegistry::load);

    /** Unloaded chunks: retry after this many ticks, doubling while it keeps happening. */
    private static final int UNLOADED_RETRY = 200;
    /**
     * Most doublings of a retry while a scan keeps coming back too large or
     * unloaded: 600 -> 9600 ticks and 200 -> 6400 ticks with the defaults.
     */
    private static final int MAX_BACKOFF = 4;
    private static final int PERIODIC_CHECK = 20;

    private final Map<UUID, Probe> probes = new LinkedHashMap<>();
    private final Map<UUID, Room> rooms = new HashMap<>();
    private final Long2ObjectOpenHashMap<Room> cellIndex = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<List<Probe>> sectionIndex = new Long2ObjectOpenHashMap<>();

    private final ArrayDeque<UUID> queue = new ArrayDeque<>();
    private final Set<UUID> queued = new HashSet<>();
    @Nullable
    private Probe activeProbe;
    @Nullable
    private RoomScan activeScan;
    private boolean activeInvalidated;

    public GreenhouseRegistry() {
    }

    // ---------------------------------------------------------------- lookups

    /** The greenhouse a plant at {@code pos} is in - checked at the plant and just above it. */
    @Nullable
    public Room roomAt(BlockPos pos) {
        if (cellIndex.isEmpty()) {
            return null;
        }
        Room room = cellIndex.get(pos.asLong());
        return room != null ? room : cellIndex.get(pos.above().asLong());
    }

    @Nullable
    public Room roomOf(UUID hygrometer) {
        Probe probe = probes.get(hygrometer);
        return probe != null ? probe.room : null;
    }

    public GreenhouseStatus statusOf(UUID hygrometer) {
        Probe probe = probes.get(hygrometer);
        return probe != null ? probe.status : GreenhouseStatus.SCANNING;
    }

    public int hygrometerCount() {
        return probes.size();
    }

    public int roomCount() {
        return rooms.size();
    }

    public int indexedCells() {
        return cellIndex.size();
    }

    public int queueLength() {
        return queue.size() + (activeScan != null ? 1 : 0);
    }

    // ------------------------------------------------------- hygrometer events

    public void register(UUID id, BlockPos pos, long now) {
        Probe probe = probes.get(id);
        if (probe != null && probe.pos.equals(pos)) {
            if (probe.status == GreenhouseStatus.SCANNING) {
                schedule(probe, now);
            }
            return;
        }
        if (probe != null) {
            unregister(id);
        }
        probe = new Probe(id, pos);
        probes.put(id, probe);
        schedule(probe, now);
        setDirty();
    }

    public void unregister(UUID id) {
        Probe probe = probes.remove(id);
        if (probe == null) {
            return;
        }
        if (activeProbe == probe) {
            activeProbe = null;
            activeScan = null;
        }
        removeFootprint(probe);
        leaveRoom(probe, true);
        queued.remove(id);
        setDirty();
    }

    /** Re-reads a hygrometer's room now - a right-click on the hygrometer asks for this. */
    public void requestScan(UUID id, long now) {
        Probe probe = probes.get(id);
        if (probe != null) {
            Probe target = scanTarget(probe);
            target.misses = 0;
            schedule(target, now);
        }
    }

    // ------------------------------------------------------- change detection

    /**
     * Called for every block that changes in this dimension, so the common
     * case is one empty-map check or one hash probe that misses.
     */
    public void onBlockChanged(BlockPos pos, BlockState oldState, BlockState newState, long now) {
        if (sectionIndex.isEmpty() && activeScan == null) {
            return;
        }
        List<Probe> watchers = sectionIndex.get(SectionPos.asLong(pos));
        boolean activeHit = activeScan != null && activeScan.reaches(pos);
        if (watchers == null && !activeHit) {
            return;
        }
        if (!matters(oldState, newState)) {
            return;
        }
        if (activeHit) {
            activeInvalidated = true;
        }
        if (watchers != null) {
            long due = now + CropClimatesConfig.GREENHOUSE_CHANGE_DELAY.get();
            for (Probe probe : watchers) {
                if (probe.footprint != null && contains(probe.footprint, pos)) {
                    probe.misses = 0;
                    if (probe.dueTick > due) {
                        probe.dueTick = due;
                    }
                }
            }
        }
    }

    /**
     * Whether a block change could change a greenhouse. Hearth air (Cold
     * Sweat's spread rule, which decides rooms) passes every block without a
     * collision shape and is stopped by every full cube, so swapping within
     * either class moves no wall: planting, harvesting, cane or kelp growing,
     * grass spreading onto dirt. A crop growing a stage or a furnace lighting
     * changes nothing either. What does count: a change of fluid, a humidity
     * source appearing or going, a block Cold Sweat's spread lists name, and
     * any change of collision shape (a door opening, a melon appearing).
     */
    private static boolean matters(BlockState oldState, BlockState newState) {
        if (!oldState.getFluidState().getType().isSame(newState.getFluidState().getType())) {
            return true;
        }
        boolean sameBlock = oldState.getBlock() == newState.getBlock();
        if (!sameBlock && (isSource(oldState) || isSource(newState))) {
            return true;
        }
        VoxelShape oldShape;
        VoxelShape newShape;
        try {
            oldShape = oldState.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            newShape = newState.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        } catch (RuntimeException ex) {
            // A modded block whose shape needs a real level: assume it matters.
            return true;
        }
        if (sameBlock) {
            return oldShape != newShape && Shapes.joinIsNotEmpty(oldShape, newShape, BooleanOp.NOT_SAME);
        }
        boolean sameClass = (oldShape.isEmpty() && newShape.isEmpty())
                || (oldShape == Shapes.block() && newShape == Shapes.block());
        return !sameClass || WorldCells.isSpreadListed(oldState) || WorldCells.isSpreadListed(newState);
    }

    private static boolean isSource(BlockState state) {
        return state.is(CropClimatesTags.DESICCANT) || state.is(CropClimatesTags.HUMIDIFIER);
    }

    private static boolean contains(int[] bounds, BlockPos pos) {
        return pos.getX() >= bounds[0] - 1 && pos.getX() <= bounds[3] + 1
                && pos.getY() >= bounds[1] - 1 && pos.getY() <= bounds[4] + 1
                && pos.getZ() >= bounds[2] - 1 && pos.getZ() <= bounds[5] + 1;
    }

    // ------------------------------------------------------------- scheduling

    /**
     * The registry's per-tick work. Runs inside the level tick, so a failure
     * here - a bug, or a modded block misbehaving under a scan - must not
     * take the server down: it switches greenhouses off for the session
     * instead, logged once, and crops fall back to outdoor humidity.
     */
    public void tick(ServerLevel level) {
        try {
            tickGuarded(level);
        } catch (RuntimeException ex) {
            WorldCells.fail(ex);
            activeScan = null;
            activeProbe = null;
        }
    }

    private void tickGuarded(ServerLevel level) {
        long now = level.getGameTime();
        if (now % PERIODIC_CHECK == 0) {
            periodic(level, now);
        }
        if (!CropClimatesConfig.GREENHOUSE_ENABLED.get() || !WorldCells.isAvailable()) {
            return;
        }
        if (now % GreenhouseParticles.INTERVAL == 0) {
            for (Room room : rooms.values()) {
                GreenhouseParticles.tick(level, room);
            }
        }

        int budget = CropClimatesConfig.GREENHOUSE_CELLS_PER_TICK.get();
        while (budget > 0) {
            if (activeScan == null && !startNext(level, now)) {
                return;
            }
            if (activeScan == null) {
                continue; // joined a room without scanning
            }
            int before = activeScan.visited();
            RoomScan.Status result = activeScan.step(budget);
            budget -= Math.max(1, activeScan.visited() - before);
            if (result != RoomScan.Status.RUNNING) {
                finish(level, activeProbe, activeScan, now);
                activeProbe = null;
                activeScan = null;
            }
        }
    }

    private void periodic(ServerLevel level, long now) {
        List<UUID> orphans = new ArrayList<>();
        for (Probe probe : probes.values()) {
            long chunk = ChunkPos.asLong(probe.pos);
            if (level.areEntitiesLoaded(chunk) && level.getEntity(probe.id) == null) {
                orphans.add(probe.id);
                continue;
            }
            if (probe.dueTick <= now && !queued.contains(probe.id)) {
                queued.add(probe.id);
                queue.add(probe.id);
            }
        }
        for (UUID orphan : orphans) {
            LOGGER.debug("crop_climates: dropping hygrometer {} - its entity is gone", orphan);
            unregister(orphan);
        }
    }

    /**
     * Brings a hygrometer's next scan forward to {@code due} at the latest.
     * Queued straight away; one popped before it is due is dropped and picked
     * up again by {@link #periodic} once it is.
     */
    private void schedule(Probe probe, long due) {
        probe.dueTick = Math.min(probe.dueTick, due);
        if (queued.add(probe.id)) {
            queue.add(probe.id);
        }
    }

    /** Rooms are rescanned by their anchor; everyone else scans for themselves. */
    private Probe scanTarget(Probe probe) {
        if (probe.room != null && !probe.isAnchor()) {
            Probe anchor = probes.get(probe.room.anchor);
            if (anchor != null) {
                return anchor;
            }
        }
        return probe;
    }

    private boolean startNext(ServerLevel level, long now) {
        while (!queue.isEmpty()) {
            UUID id = queue.poll();
            queued.remove(id);
            Probe probe = probes.get(id);
            if (probe == null) {
                continue;
            }
            Probe target = scanTarget(probe);
            if (target != probe) {
                probe.dueTick = Long.MAX_VALUE; // its anchor scans for it
            }
            probe = target;
            if (probe.dueTick > now) {
                continue;
            }
            if (!level.isLoaded(probe.pos)) {
                probe.dueTick = now + UNLOADED_RETRY;
                continue;
            }
            if (probe.room == null) {
                Room existing = cellIndex.get(probe.pos.asLong());
                if (existing != null && probes.containsKey(existing.anchor)) {
                    join(probe, existing);
                    setDirty();
                    activeScan = null;
                    return true;
                }
            }
            probe.dueTick = Long.MAX_VALUE; // until the scan finishes
            WorldCells cells = new WorldCells(level);
            activeProbe = probe;
            activeInvalidated = false;
            activeScan = new RoomScan(probe.pos, cells, cells, new RoomScan.Limits(
                    CropClimatesConfig.greenhouseMaxVolume(), CropClimatesConfig.GREENHOUSE_MIN_VOLUME.get()));
            return true;
        }
        return false;
    }

    private void finish(ServerLevel level, Probe probe, RoomScan scan, long now) {
        if (!probes.containsKey(probe.id)) {
            return;
        }
        GreenhouseStatus statusBefore = probe.status;
        Room roomBefore = probe.room;
        removeFootprint(probe);
        RoomScan.Status result = scan.status();

        if (result == RoomScan.Status.UNLOADED) {
            // Keep whatever room we had; try again once more is loaded. A room
            // left half in unloaded chunks would otherwise re-flood its loaded
            // half every 200 ticks for as long as it stays that way.
            probe.dueTick = now + ((long) UNLOADED_RETRY << Math.min(probe.misses, MAX_BACKOFF + 1));
            probe.misses++;
            if (probe.room == null) {
                probe.status = GreenhouseStatus.SCANNING;
            } else {
                addFootprint(probe, probe.room.bounds);
            }
            if (probe.status != statusBefore) {
                setDirty();
            }
            return;
        }

        addFootprint(probe, scan.bounds());
        boolean changed;
        if (result == RoomScan.Status.ENCLOSED) {
            changed = installRoom(probe, scan, now);
            probe.misses = 0;
            probe.dueTick = now + stagger(probe, CropClimatesConfig.GREENHOUSE_RESCAN_INTERVAL.get());
        } else {
            changed = probe.room != null;
            if (probe.isAnchor()) {
                dissolve(probe.room, now);
            } else {
                leaveRoom(probe, true);
            }
            probe.status = switch (result) {
                case TOO_LARGE -> GreenhouseStatus.TOO_LARGE;
                case TOO_SMALL -> GreenhouseStatus.TOO_SMALL;
                default -> GreenhouseStatus.OUTDOOR;
            };
            int retry = CropClimatesConfig.GREENHOUSE_UNSEALED_RETRY_INTERVAL.get();
            if (result == RoomScan.Status.TOO_LARGE) {
                // A cave or a Nether cavern never seals: each retry floods a whole
                // max-size room's worth of cells, so back off while nothing changes.
                // Outdoor and too-small retries stay prompt - they are cheap, and a
                // player closing a room up expects it to become a greenhouse.
                retry <<= Math.min(probe.misses, MAX_BACKOFF);
                probe.misses++;
            } else {
                probe.misses = 0;
            }
            probe.dueTick = now + stagger(probe, retry);
        }
        if (activeInvalidated) {
            probe.dueTick = Math.min(probe.dueTick, now + CropClimatesConfig.GREENHOUSE_CHANGE_DELAY.get());
        }
        // A routine rescan of an unchanged room has nothing new to save, and
        // re-serialising every room's cells on each autosave is not free.
        if (changed || probe.status != statusBefore || probe.room != roomBefore) {
            setDirty();
        }
    }

    /** Spreads routine rescans out so rooms placed together do not rescan together. */
    private static long stagger(Probe probe, int interval) {
        return interval + Math.floorMod(probe.id.hashCode(), Math.max(1, interval / 4));
    }

    /** Installs an enclosed scan as {@code anchor}'s room; returns whether anything worth saving changed. */
    private boolean installRoom(Probe anchor, RoomScan scan, long now) {
        Room room = anchor.isAnchor() ? anchor.room : null;
        boolean changed = false;
        if (room == null) {
            leaveRoom(anchor, true);
            room = new Room(UUID.randomUUID());
            rooms.put(room.id, room);
            changed = true;
        }
        LongOpenHashSet previousCells = room.interior;
        changed |= room.update(scan, anchor.id, now);

        // Any other greenhouse this scan now overlaps (a wall came down
        // between two rooms) merges into this one.
        Set<Room> absorbed = reindex(room, previousCells);
        changed |= !absorbed.isEmpty();
        for (Room other : absorbed) {
            rooms.remove(other.id);
            unindex(other);
            for (UUID memberId : List.copyOf(other.members)) {
                Probe member = probes.get(memberId);
                if (member != null) {
                    member.room = null;
                    member.status = GreenhouseStatus.SCANNING;
                    schedule(member, now); // joins below if it is inside
                }
            }
            other.members.clear();
        }

        // Members that are no longer inside leave; hygrometers that are inside join.
        for (UUID memberId : List.copyOf(room.members)) {
            Probe member = probes.get(memberId);
            if (member == null || !room.interior.contains(member.pos.asLong())) {
                room.members.remove(memberId);
                changed = true;
                if (member != null) {
                    member.room = null;
                    member.status = GreenhouseStatus.SCANNING;
                    schedule(member, now);
                }
            }
        }
        for (Probe probe : probes.values()) {
            if (probe.room != room && room.interior.contains(probe.pos.asLong())) {
                if (probe.room != null) {
                    probe.room.members.remove(probe.id);
                }
                join(probe, room);
                changed = true;
            }
        }
        anchor.room = room;
        anchor.status = GreenhouseStatus.GREENHOUSE;
        changed |= room.members.add(anchor.id);
        return changed;
    }

    /**
     * Brings the cell index in line with a room's new interior by difference,
     * so rescanning an unchanged room costs lookups only. Returns the other
     * rooms whose cells it now covers.
     *
     * <p>Removing every cell and putting them back in the new set's hash
     * order made fastutil's linear probing cluster - about 350 ms for one
     * rescan of a max-size room. Bulk inserts are sized first for the same
     * reason: inserting in another table's hash order into a table that
     * grows as it goes clusters just as badly.
     */
    private Set<Room> reindex(Room room, LongOpenHashSet previousCells) {
        if (previousCells != room.interior) {
            for (LongIterator it = previousCells.iterator(); it.hasNext(); ) {
                long cell = it.nextLong();
                if (!room.interior.contains(cell) && cellIndex.get(cell) == room) {
                    cellIndex.remove(cell);
                }
            }
        }
        int missing = 0;
        for (LongIterator it = room.interior.iterator(); it.hasNext(); ) {
            if (cellIndex.get(it.nextLong()) != room) {
                missing++;
            }
        }
        Set<Room> absorbed = new HashSet<>();
        if (missing > 0) {
            cellIndex.ensureCapacity(cellIndex.size() + missing);
            for (LongIterator it = room.interior.iterator(); it.hasNext(); ) {
                Room previous = cellIndex.put(it.nextLong(), room);
                if (previous != null && previous != room) {
                    absorbed.add(previous);
                }
            }
        }
        return absorbed;
    }

    private void join(Probe probe, Room room) {
        if (probe.footprint != null && !probe.id.equals(room.anchor)) {
            removeFootprint(probe);
        }
        probe.room = room;
        probe.status = GreenhouseStatus.GREENHOUSE;
        room.members.add(probe.id);
        if (!probe.id.equals(room.anchor)) {
            probe.dueTick = Long.MAX_VALUE; // the anchor rescans for everyone
        }
    }

    /**
     * Takes a hygrometer out of its room. When the anchor leaves, the room
     * either passes to another member (rescanned from there) or, with
     * {@code dissolveIfLast} and nobody left, is deleted.
     */
    private void leaveRoom(Probe probe, boolean dissolveIfLast) {
        Room room = probe.room;
        if (room == null) {
            return;
        }
        probe.room = null;
        room.members.remove(probe.id);
        if (!probe.id.equals(room.anchor)) {
            return;
        }
        Probe successor = room.members.stream().map(probes::get).filter(p -> p != null).findFirst().orElse(null);
        if (successor != null) {
            room.anchor = successor.id;
            schedule(successor, 0);
        } else if (dissolveIfLast) {
            rooms.remove(room.id);
            unindex(room);
        }
    }

    /** The room is gone (its anchor's scan leaked): every member looks again for itself. */
    private void dissolve(Room room, long now) {
        rooms.remove(room.id);
        unindex(room);
        for (UUID memberId : List.copyOf(room.members)) {
            Probe member = probes.get(memberId);
            if (member != null) {
                member.room = null;
                member.status = GreenhouseStatus.SCANNING;
                if (!member.id.equals(room.anchor)) {
                    schedule(member, now);
                }
            }
        }
        room.members.clear();
    }

    private void unindex(Room room) {
        for (LongIterator it = room.interior.iterator(); it.hasNext(); ) {
            long cell = it.nextLong();
            if (cellIndex.get(cell) == room) {
                cellIndex.remove(cell);
            }
        }
    }

    private void addFootprint(Probe probe, int[] bounds) {
        probe.footprint = bounds;
        forEachSection(bounds, key -> sectionIndex.computeIfAbsent(key, k -> new ArrayList<>(2)).add(probe));
    }

    private void removeFootprint(Probe probe) {
        if (probe.footprint == null) {
            return;
        }
        forEachSection(probe.footprint, key -> {
            List<Probe> list = sectionIndex.get(key);
            if (list != null) {
                list.remove(probe);
                if (list.isEmpty()) {
                    sectionIndex.remove(key);
                }
            }
        });
        probe.footprint = null;
    }

    private static void forEachSection(int[] b, java.util.function.LongConsumer action) {
        int sx0 = SectionPos.blockToSectionCoord(b[0] - 1), sx1 = SectionPos.blockToSectionCoord(b[3] + 1);
        int sy0 = SectionPos.blockToSectionCoord(b[1] - 1), sy1 = SectionPos.blockToSectionCoord(b[4] + 1);
        int sz0 = SectionPos.blockToSectionCoord(b[2] - 1), sz1 = SectionPos.blockToSectionCoord(b[5] + 1);
        for (int x = sx0; x <= sx1; x++) {
            for (int y = sy0; y <= sy1; y++) {
                for (int z = sz0; z <= sz1; z++) {
                    action.accept(SectionPos.asLong(x, y, z));
                }
            }
        }
    }

    // ------------------------------------------------------------ persistence

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag roomList = new ListTag();
        for (Room room : rooms.values()) {
            roomList.add(room.save());
        }
        tag.put("Rooms", roomList);

        ListTag probeList = new ListTag();
        for (Probe probe : probes.values()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Id", probe.id);
            entry.put("Pos", NbtUtils.writeBlockPos(probe.pos));
            if (probe.room != null) {
                entry.putUUID("Room", probe.room.id);
            }
            entry.putString("Status", probe.status.name());
            probeList.add(entry);
        }
        tag.put("Hygrometers", probeList);
        return tag;
    }

    /**
     * Rooms come back with their cells, so crops resolve immediately; every
     * hygrometer is due for a fresh scan on the first tick to catch anything
     * that changed while the world was off.
     */
    static GreenhouseRegistry load(CompoundTag tag, HolderLookup.Provider registries) {
        GreenhouseRegistry registry = new GreenhouseRegistry();
        int cells = 0;
        for (Tag entry : tag.getList("Rooms", Tag.TAG_COMPOUND)) {
            Room room = Room.load((CompoundTag) entry);
            room.members.clear();
            registry.rooms.put(room.id, room);
            cells += room.interior.size();
        }
        // Sized up front - see reindex() on why hash-order inserts must not grow the table.
        registry.cellIndex.ensureCapacity(cells);
        for (Room room : registry.rooms.values()) {
            for (LongIterator it = room.interior.iterator(); it.hasNext(); ) {
                registry.cellIndex.put(it.nextLong(), room);
            }
        }
        for (Tag raw : tag.getList("Hygrometers", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) raw;
            BlockPos pos = NbtUtils.readBlockPos(entry, "Pos").orElse(null);
            if (pos == null) {
                continue;
            }
            Probe probe = new Probe(entry.getUUID("Id"), pos);
            if (entry.hasUUID("Room")) {
                Room room = registry.rooms.get(entry.getUUID("Room"));
                if (room != null) {
                    probe.room = room;
                    room.members.add(probe.id);
                }
            }
            try {
                probe.status = GreenhouseStatus.valueOf(entry.getString("Status"));
            } catch (IllegalArgumentException ex) {
                probe.status = GreenhouseStatus.SCANNING;
            }
            if (probe.room != null && probe.room.anchor.equals(probe.id)) {
                registry.addFootprint(probe, probe.room.bounds);
            }
            probe.dueTick = 0;
            registry.probes.put(probe.id, probe);
        }
        // Rooms nobody points at any more cannot be rescanned - drop them.
        for (Room room : List.copyOf(registry.rooms.values())) {
            if (room.members.isEmpty() || !room.members.contains(room.anchor)) {
                if (!room.members.isEmpty()) {
                    room.anchor = room.members.iterator().next();
                    continue;
                }
                registry.rooms.remove(room.id);
                registry.unindex(room);
            }
        }
        for (Probe probe : registry.probes.values()) {
            registry.queued.add(probe.id);
            registry.queue.add(probe.id);
        }
        return registry;
    }
}
