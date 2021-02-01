package net.snakefangox.worldshell.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Predicate;

import net.snakefangox.worldshell.WSNetworking;
import net.snakefangox.worldshell.entity.WorldLinkEntity;
import net.snakefangox.worldshell.util.ShellTransferHandler;
import net.snakefangox.worldshell.util.WorldShellPacketHelper;
import org.jetbrains.annotations.Nullable;

import net.minecraft.block.BlockState;
import net.minecraft.class_5575;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.WorldGenerationProgressListener;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.world.World;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.gen.Spawner;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.level.ServerWorldProperties;
import net.minecraft.world.level.storage.LevelStorage;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public class ShellStorageWorld extends ServerWorld {

	private ShellStorageData cachedBayData;

	public ShellStorageWorld(MinecraftServer server, Executor workerExecutor, LevelStorage.Session session, ServerWorldProperties properties, RegistryKey<World> registryKey, DimensionType dimensionType, WorldGenerationProgressListener worldGenerationProgressListener, ChunkGenerator chunkGenerator, boolean debugWorld, long l, List<Spawner> spawners, boolean shouldTickTime) {
		super(server, workerExecutor, session, properties, registryKey, dimensionType, worldGenerationProgressListener, chunkGenerator, debugWorld, l, spawners, shouldTickTime);
	}

	@Override
	public boolean setBlockState(BlockPos pos, BlockState state, int flags, int maxUpdateDepth) {
		boolean changed = super.setBlockState(pos, state, flags, maxUpdateDepth);
		if (changed) {
			passCallToEntity(pos, (entity, bay) -> PlayerLookup.tracking(entity).forEach(player -> {
				PacketByteBuf buf = PacketByteBufs.create();
				WorldShellPacketHelper.writeBlock(buf, this, pos, entity, bay.getCenter());
				ServerPlayNetworking.send(player, WSNetworking.SHELL_UPDATE, buf);
				if (!bay.getBounds().contains(pos)) {
					ShellTransferHandler.updateBoxBounds(bay.getBounds(), pos);
					bay.markDirty(this);
				}
			}));
		}
		return changed;
	}

	@Override
	public boolean spawnEntity(Entity entity) {
		return passCallToEntity(entity.getBlockPos(), false, (worldLinkEntity, bay) -> {
			Vec3d newPos = bay.toEntityCoordSpace(entity.getPos());
			entity.setPosition(newPos.x, newPos.y, newPos.z);
			return worldLinkEntity.getEntityWorld().spawnEntity(entity);
		});
	}

	@Override
	public <T extends ParticleEffect> int spawnParticles(T particle, double x, double y, double z, int count, double deltaX, double deltaY, double deltaZ, double speed) {
		return passCallToEntity(new BlockPos(x, y, z), 0, (entity, bay) -> {
			Vec3d newPos = bay.toEntityCoordSpace(x, y, z);
			return ((ServerWorld) entity.getEntityWorld()).spawnParticles(particle, newPos.x, newPos.y, newPos.z, count, deltaX, deltaY, deltaZ, speed);
		});
	}

	@Override
	public <T extends ParticleEffect> boolean spawnParticles(ServerPlayerEntity viewer, T particle, boolean force, double x, double y, double z, int count, double deltaX, double deltaY, double deltaZ, double speed) {
		return passCallToEntity(new BlockPos(x, y, z), false, (entity, bay) -> {
			Vec3d newPos = bay.toEntityCoordSpace(x, y, z);
			return ((ServerWorld) entity.getEntityWorld()).spawnParticles(viewer, particle, force, newPos.x, newPos.y, newPos.z, count, deltaX, deltaY, deltaZ, speed);
		});
	}

	@Override
	public void playSound(@Nullable PlayerEntity player, double x, double y, double z, SoundEvent sound, SoundCategory category, float volume, float pitch) {
		passCallToEntity(new BlockPos(x, y, z), (entity, bay) -> {
			Vec3d newPos = bay.toEntityCoordSpace(x, y, z);
			entity.getEntityWorld().playSound(player, newPos.x, newPos.y, newPos.z, sound, category, volume, pitch);
		});
	}

	@Override
	public <T extends Entity> List<T> getEntitiesByType(class_5575<Entity, T> arg, Box box, Predicate<? super T> predicate) {
		return passCallToEntity(new BlockPos(box.minX, box.minY, box.minZ), new ArrayList<>(), ((entity, bay) -> {
			Vec3d newMin = bay.toEntityCoordSpace(box.minX, box.minY, box.minZ);
			Vec3d newMax = bay.toEntityCoordSpace(box.maxX, box.maxY, box.maxZ);
			return entity.getEntityWorld().getEntitiesByType(arg, new Box(newMin, newMax), predicate);
		}));
	}

	@Override
	public List<Entity> getOtherEntities(@Nullable Entity except, Box box, Predicate<? super Entity> predicate) {
		return passCallToEntity(new BlockPos(box.minX, box.minY, box.minZ), new ArrayList<>(), ((entity, bay) -> {
			Vec3d newMin = bay.toEntityCoordSpace(box.minX, box.minY, box.minZ);
			Vec3d newMax = bay.toEntityCoordSpace(box.maxX, box.maxY, box.maxZ);
			return entity.getEntityWorld().getOtherEntities(except, new Box(newMin, newMax), predicate);
		}));
	}

	private <T> T passCallToEntity(BlockPos pos, T defaultVal, EntityPassthroughFunction<T> consumer) {
		ShellBay bay = cachedBayData.getBay(cachedBayData.getBayIdFromPos(pos));
		if (bay != null && bay.getLinkedEntity().isPresent()) {
			return consumer.passthrough(bay.getLinkedEntity().get(), bay);
		}
		return defaultVal;
	}

	private void passCallToEntity(BlockPos pos, EntityPassthroughConsumer consumer) {
		ShellBay bay = cachedBayData.getBay(cachedBayData.getBayIdFromPos(pos));
		if (bay != null && bay.getLinkedEntity().isPresent()) {
			consumer.passthrough(bay.getLinkedEntity().get(), bay);
		}
	}

	public void setCachedBayData(ShellStorageData cachedBayData) {
		this.cachedBayData = cachedBayData;
	}

	public interface EntityPassthroughConsumer {
		void passthrough(WorldLinkEntity entity, ShellBay bay);
	}

	public interface EntityPassthroughFunction<T> {
		T passthrough(WorldLinkEntity entity, ShellBay bay);
	}
}