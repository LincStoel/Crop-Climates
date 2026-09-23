package com.worldtraveler.cropclimates.entity;

import com.worldtraveler.cropclimates.CropClimates;
import com.worldtraveler.cropclimates.climate.HumiditySource;
import com.worldtraveler.cropclimates.climate.TemperatureUnits;
import com.worldtraveler.cropclimates.greenhouse.GreenhouseRegistry;
import com.worldtraveler.cropclimates.greenhouse.GreenhouseStatus;
import com.worldtraveler.cropclimates.greenhouse.Greenhouses;
import com.worldtraveler.cropclimates.greenhouse.Room;
import com.worldtraveler.cropclimates.report.Reports;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.level.BlockEvent;

import javax.annotation.Nullable;

/**
 * A wall-hung hygrometer, like an item frame. It establishes a greenhouse:
 * the {@link GreenhouseRegistry} scans the room it hangs in, and every crop
 * inside then grows by the room's humidity. Hung anywhere not sealed, it just
 * reads the outdoor air (rain included) and keeps checking, so closing the
 * room up turns it into a greenhouse on its own.
 *
 * <p>Humidity and status are synced to clients for the dial and Jade; the
 * right-click report is built server side.
 */
public class HygrometerEntity extends HangingEntity {

    private static final EntityDataAccessor<Float> DATA_HUMIDITY =
            SynchedEntityData.defineId(HygrometerEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> DATA_STATUS =
            SynchedEntityData.defineId(HygrometerEntity.class, EntityDataSerializers.INT);

    private static final double DEPTH = 0.0625;
    private static final double SIZE = 0.625;
    private static final int REFRESH_TICKS = 20;
    private static final int REPORT_COOLDOWN = 20;

    /** Game time of the last right-click report; starts far enough back that the first click always reports. */
    private long lastReport = -REPORT_COOLDOWN;

    public HygrometerEntity(EntityType<? extends HygrometerEntity> type, Level level) {
        super(type, level);
    }

    public HygrometerEntity(Level level, BlockPos pos, Direction direction) {
        super(CropClimates.HYGROMETER_ENTITY.get(), level, pos);
        this.setDirection(direction);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_HUMIDITY, 0.0F);
        builder.define(DATA_STATUS, GreenhouseStatus.SCANNING.ordinal());
    }

    /** Humidity (0-1) the dial shows: the greenhouse's, or the outdoor air's. */
    public float humidity() {
        return entityData.get(DATA_HUMIDITY);
    }

    public GreenhouseStatus status() {
        return GreenhouseStatus.byId(entityData.get(DATA_STATUS));
    }

    // -------------------------------------------------------------- placement

    @Override
    protected AABB calculateBoundingBox(BlockPos pos, Direction direction) {
        Vec3 center = Vec3.atCenterOf(pos).relative(direction, -0.46875);
        Direction.Axis axis = direction.getAxis();
        double dx = axis == Direction.Axis.X ? DEPTH : SIZE;
        double dz = axis == Direction.Axis.Z ? DEPTH : SIZE;
        return AABB.ofSize(center, dx, SIZE, dz);
    }

    @Override
    public boolean survives() {
        if (!level().noCollision(this)) {
            return false;
        }
        BlockState behind = level().getBlockState(pos.relative(direction.getOpposite()));
        return (behind.isSolid() || DiodeBlock.isDiode(behind))
                && level().getEntities(this, getBoundingBox(), HANGING_ENTITY).isEmpty();
    }

    /**
     * Drops hygrometers the moment the block holding them goes, like a torch,
     * instead of waiting for {@link HangingEntity}'s 100-tick survival check.
     */
    public static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || event.getState().isSolid()) {
            return;
        }
        for (HygrometerEntity hygrometer : level.getEntitiesOfClass(
                HygrometerEntity.class, new AABB(event.getPos()).inflate(1.0))) {
            if (!hygrometer.isRemoved() && !hygrometer.survives()) {
                hygrometer.discard();
                hygrometer.dropItem(null);
            }
        }
    }

    @Override
    public void playPlacementSound() {
        playSound(SoundEvents.ITEM_FRAME_PLACE, 1.0F, 1.0F);
    }

    @Override
    public void dropItem(@Nullable Entity breaker) {
        playSound(SoundEvents.ITEM_FRAME_BREAK, 1.0F, 1.0F);
        if (!level().getGameRules().getBoolean(GameRules.RULE_DOENTITYDROPS)) {
            return;
        }
        if (breaker instanceof Player player && player.hasInfiniteMaterials()) {
            return;
        }
        spawnAtLocation(new ItemStack(CropClimates.HYGROMETER.get()));
    }

    @Override
    public ItemStack getPickResult() {
        return new ItemStack(CropClimates.HYGROMETER.get());
    }

    // -------------------------------------------------------------- lifecycle

    @Override
    public void onAddedToLevel() {
        super.onAddedToLevel();
        if (level() instanceof ServerLevel serverLevel) {
            Greenhouses.get(serverLevel).register(getUUID(), pos, serverLevel.getGameTime());
            refresh(serverLevel);
        }
    }

    @Override
    public void remove(RemovalReason reason) {
        if (reason.shouldDestroy() && level() instanceof ServerLevel serverLevel) {
            Greenhouses.get(serverLevel).unregister(getUUID());
        }
        super.remove(reason);
    }

    @Override
    public void tick() {
        super.tick();
        if (level() instanceof ServerLevel serverLevel && !isRemoved() && tickCount % REFRESH_TICKS == 0) {
            refresh(serverLevel);
        }
    }

    private void refresh(ServerLevel level) {
        GreenhouseStatus status;
        double humidity;
        Room room = null;
        if (!Greenhouses.enabled()) {
            status = GreenhouseStatus.DISABLED;
        } else {
            GreenhouseRegistry registry = Greenhouses.get(level);
            status = registry.statusOf(getUUID());
            room = registry.roomOf(getUUID());
        }
        if (status == GreenhouseStatus.GREENHOUSE && room != null) {
            humidity = room.humidity(level);
        } else {
            humidity = HumiditySource.outdoor(level, pos).humidity();
            if (status == GreenhouseStatus.GREENHOUSE) {
                status = GreenhouseStatus.SCANNING;
            }
        }
        entityData.set(DATA_HUMIDITY, (float) humidity);
        entityData.set(DATA_STATUS, status.ordinal());
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (!(level() instanceof ServerLevel serverLevel)) {
            return InteractionResult.SUCCESS;
        }
        long now = serverLevel.getGameTime();
        if (now >= lastReport && now - lastReport < REPORT_COOLDOWN) {
            return InteractionResult.CONSUME;
        }
        lastReport = now;
        refresh(serverLevel);
        GreenhouseRegistry registry = Greenhouses.get(serverLevel);
        Room room = Greenhouses.enabled() ? registry.roomOf(getUUID()) : null;
        Reports.hygrometer(serverLevel, pos, status(), room, TemperatureUnits.forPlayer(player))
                .send(line -> player.displayClientMessage(line, false));
        registry.requestScan(getUUID(), now);
        return InteractionResult.CONSUME;
    }

    // ------------------------------------------------------------ persistence

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putByte("Facing", (byte) direction.get2DDataValue());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        setDirection(Direction.from2DDataValue(tag.getByte("Facing")));
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket(ServerEntity entity) {
        return new ClientboundAddEntityPacket(this, direction.get3DDataValue(), getPos());
    }

    @Override
    public void recreateFromPacket(ClientboundAddEntityPacket packet) {
        super.recreateFromPacket(packet);
        setDirection(Direction.from3DDataValue(packet.getData()));
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        double range = 16.0 * 64.0 * getViewScale();
        return distance < range * range;
    }
}
