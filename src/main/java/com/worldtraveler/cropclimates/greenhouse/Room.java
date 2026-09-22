package com.worldtraveler.cropclimates.greenhouse;

import com.worldtraveler.cropclimates.CropClimatesConfig;
import com.worldtraveler.cropclimates.climate.BiomeMoisture;
import com.worldtraveler.cropclimates.climate.EnclosureHumidity;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * One sealed greenhouse: the interior cells a hygrometer's scan found, what
 * the scan counted inside, and which hygrometers share it. Owned and indexed
 * by {@link GreenhouseRegistry}.
 *
 * <p>Humidity starts from the biome <em>at the anchor hygrometer</em>, even
 * when the room crosses a biome border, then shifts by the room's net source
 * weight diluted over its interior (air, water and plants - not the walls).
 * Rain never reaches it.
 */
public final class Room {

    final UUID id;
    UUID anchor;
    BlockPos anchorPos;
    final Set<UUID> members = new LinkedHashSet<>();
    LongOpenHashSet interior = new LongOpenHashSet();
    int[] bounds = new int[6];
    int netWeight;
    final int[] sourceCounts = new int[EnclosureHumidity.Effect.values().length];
    long lastScan;
    /** {@link #interior} as an array, for picking random cells; rebuilt lazily after each scan. */
    private long[] cellArray;

    Room(UUID id) {
        this.id = id;
    }

    void update(RoomScan scan, UUID anchor, long now) {
        this.anchor = anchor;
        this.anchorPos = scan.origin();
        this.interior = scan.interior();
        this.cellArray = null;
        this.bounds = scan.bounds();
        this.netWeight = scan.tally().net();
        for (EnclosureHumidity.Effect effect : EnclosureHumidity.Effect.values()) {
            sourceCounts[effect.ordinal()] = scan.tally().count(effect);
        }
        this.lastScan = now;
    }

    /** Biome humidity at the anchor hygrometer, before the room's own sources. */
    public double baseHumidity(Level level) {
        return BiomeMoisture.moistureOf(level.getBiome(anchorPos), CropClimatesConfig.DEFAULT_BIOME_MOISTURE.get());
    }

    public double humidity(Level level) {
        return EnclosureHumidity.compute(baseHumidity(level), netWeight, interior.size(),
                CropClimatesConfig.HUMIDITY_BLOCK_MULTIPLIER.get());
    }

    /** A uniformly random interior cell, packed with {@link BlockPos#asLong}. */
    public long randomCell(net.minecraft.util.RandomSource random) {
        if (cellArray == null) {
            cellArray = interior.toLongArray();
        }
        return cellArray[random.nextInt(cellArray.length)];
    }

    /** Whether a packed position is one of this room's interior cells. */
    public boolean containsCell(long packed) {
        return interior.contains(packed);
    }

    public BlockPos anchorPos() {
        return anchorPos;
    }

    /** Interior cells - the greenhouse's size as the cap counts it. */
    public int size() {
        return interior.size();
    }

    public int sourceCount(EnclosureHumidity.Effect effect) {
        return sourceCounts[effect.ordinal()];
    }

    public int memberCount() {
        return members.size();
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putUUID("Anchor", anchor);
        tag.put("AnchorPos", NbtUtils.writeBlockPos(anchorPos));
        ListTag memberList = new ListTag();
        for (UUID member : members) {
            memberList.add(NbtUtils.createUUID(member));
        }
        tag.put("Members", memberList);
        tag.put("Interior", new LongArrayTag(interior.toLongArray()));
        tag.putIntArray("Bounds", bounds);
        tag.putInt("NetWeight", netWeight);
        tag.putIntArray("Sources", sourceCounts);
        tag.putLong("LastScan", lastScan);
        return tag;
    }

    static Room load(CompoundTag tag) {
        Room room = new Room(tag.getUUID("Id"));
        room.anchor = tag.getUUID("Anchor");
        room.anchorPos = NbtUtils.readBlockPos(tag, "AnchorPos").orElse(BlockPos.ZERO);
        for (Tag member : tag.getList("Members", Tag.TAG_INT_ARRAY)) {
            room.members.add(NbtUtils.loadUUID(member));
        }
        room.interior = new LongOpenHashSet(tag.getLongArray("Interior"));
        int[] bounds = tag.getIntArray("Bounds");
        room.bounds = bounds.length == 6 ? bounds : new int[6];
        room.netWeight = tag.getInt("NetWeight");
        int[] sources = tag.getIntArray("Sources");
        System.arraycopy(sources, 0, room.sourceCounts, 0, Math.min(sources.length, room.sourceCounts.length));
        room.lastScan = tag.getLong("LastScan");
        return room;
    }
}
