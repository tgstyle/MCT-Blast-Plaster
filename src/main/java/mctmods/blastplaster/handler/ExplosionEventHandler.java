package mctmods.blastplaster.handler;

import mctmods.blastplaster.BlastPlaster;
import mctmods.blastplaster.Config;
import mctmods.blastplaster.Config.ExplosionMode;
import mctmods.blastplaster.helper.BlockStatePosWrapper;
import mctmods.blastplaster.util.BlastPlasterUtil;
import mctmods.blastplaster.worldhealer.WorldHealerSaveDataSupplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.WitherSkull;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public class ExplosionEventHandler {

  public ExplosionEventHandler() { MinecraftForge.EVENT_BUS.register(this); }

  private static long lastFlashTick = 0;
  private static final Map<BlockPos, Long> lastProcessedPositions = new HashMap<>();

  @SubscribeEvent public void onDetonate(ExplosionEvent.Detonate event) {
    if (event.getLevel().isClientSide) { return; }

    Explosion explosion = event.getExplosion();
    Entity exploder = explosion.getDirectSourceEntity();
    LivingEntity indirect = explosion.getIndirectSourceEntity();

    boolean isPlayerIgnitedTNT = false;
    if (exploder instanceof PrimedTnt primed) {
      LivingEntity owner = primed.getOwner();
      isPlayerIgnitedTNT = (owner instanceof Player) || (indirect instanceof Player);
    }

    boolean isCreeper = exploder instanceof Creeper;

    boolean processThis = Config.healAll() || (Config.processPlayerIgnitedTNT() && isPlayerIgnitedTNT);

    if (!processThis) {
      if (Config.healCreepers() && isCreeper) { processThis = true; }
      if (!processThis && Config.healWither() && (exploder instanceof WitherBoss || exploder instanceof WitherSkull)) { processThis = true; }
      if (!processThis && Config.healNonPlayerTNT()) {
        boolean nonPlayerCaused = !(indirect instanceof Player);
        boolean isPrimedTnt = exploder instanceof PrimedTnt;
        boolean isCustomEntity = false;
        for (String idStr : Config.getCustomEntitiesToHeal()) {
          ResourceLocation id = ResourceLocation.tryParse(idStr);
          if (id != null) {
            EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(id);
            if (type != null && ((exploder != null && exploder.getType() == type) || (indirect != null && indirect.getType() == type))) {
              isCustomEntity = true;
              break;
            }
          }
        }
        if (nonPlayerCaused && (isPrimedTnt || isCustomEntity)) { processThis = true; }
      }
    }

    if (processThis) {
      ServerLevel serverLevel = (ServerLevel) event.getLevel();
      Vec3 explosionCenter = explosion.getPosition();

      long currentTick = serverLevel.getGameTime();
      lastProcessedPositions.entrySet().removeIf(e -> currentTick - e.getValue() > 600L);

      if (Config.enableExplosionFlash()) { spawnImmediateExplosionVisuals(serverLevel, explosionCenter); }
      if (Config.enableExplosionSmoke()) { spawnExplosionSmoke(serverLevel, explosionCenter); }

      ExplosionMode mode = Config.getExplosionMode();
      ExplosionMode effectiveMode = (isPlayerIgnitedTNT && Config.playerTNTAlwaysDrops()) ? ExplosionMode.EJECT_DROPS : mode;
      WorldHealerSaveDataSupplier worldHealer = (effectiveMode == ExplosionMode.HEAL) ? BlastPlaster.getWorldHealer(serverLevel) : null;

      boolean forcePlayerTNTDrops = isPlayerIgnitedTNT && Config.playerTNTAlwaysDrops();
      boolean indirectIsPlayer = indirect instanceof Player;

      var toProcess = new ArrayList<BlockStatePosWrapper>();
      var affectedPos = new HashSet<BlockPos>();
      for (BlockPos pos : event.getAffectedBlocks()) {
        BlockState state = serverLevel.getBlockState(pos);
        if (state.isAir()) { continue; }
        BlockStatePosWrapper wrapper = new BlockStatePosWrapper(serverLevel, pos, state);
        toProcess.add(wrapper);
        affectedPos.add(pos);
      }

      if (!toProcess.isEmpty()) {
        if (Config.healFullTrees()) {
          WorldHealerSaveDataSupplier expansionHealer = BlastPlaster.getWorldHealer(serverLevel);
          if (expansionHealer != null) { expansionHealer.addExtraTreeBlocks(toProcess, affectedPos, serverLevel); }
        }

        if (effectiveMode != ExplosionMode.EJECT_DROPS) { BlastPlasterUtil.addAttachedCocoaPods(toProcess, affectedPos, serverLevel); }
        if (effectiveMode != ExplosionMode.EJECT_DROPS) { BlastPlasterUtil.addBambooVerticals(toProcess, affectedPos, serverLevel); }

        if (effectiveMode != ExplosionMode.EJECT_DROPS && Config.enableDropScavenger()) { BlastPlasterUtil.recordExplosionArea(serverLevel, affectedPos); }

        explosion.getToBlow().removeAll(affectedPos);

        List<BlockStatePosWrapper> toDestroy = new ArrayList<>(toProcess);

        if (worldHealer != null) { worldHealer.prepareAndScheduleHealing(toProcess, affectedPos, serverLevel); }

        List<BlastPlasterUtil.PendingDrop> pendingRealDrops = new ArrayList<>();

        if (effectiveMode == ExplosionMode.EJECT_DROPS) {
          if (forcePlayerTNTDrops) {
            for (BlockStatePosWrapper wrapper : toProcess) {
              BlockPos pos = wrapper.getPos();
              BlockState state = wrapper.getState();
              if (BlastPlasterUtil.isDynamicTrees(state) && Config.dtSpecialDrops()) { BlastPlasterUtil.addDynamicTreesDropsToPending(pendingRealDrops, serverLevel, pos, state, true); } else if (Config.playerTNTDropFullBlocks()) { pendingRealDrops.add(new BlastPlasterUtil.PendingDrop(Vec3.atCenterOf(pos), new ItemStack(state.getBlock()), true)); } else {
                state.spawnAfterBreak(serverLevel, pos, ItemStack.EMPTY, indirectIsPlayer);
                BlockEntity be = serverLevel.getBlockEntity(pos);
                LootParams.Builder builder = new LootParams.Builder(serverLevel)
                        .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
                        .withParameter(LootContextParams.TOOL, ItemStack.EMPTY)
                        .withOptionalParameter(LootContextParams.BLOCK_ENTITY, be)
                        .withOptionalParameter(LootContextParams.THIS_ENTITY, exploder);
                for (ItemStack stack : state.getDrops(builder)) {
                  if (!stack.isEmpty()) { pendingRealDrops.add(new BlastPlasterUtil.PendingDrop(Vec3.atCenterOf(pos), stack, true)); }
                }
              }
            }
          }

          if (isCreeper) {
            for (BlockStatePosWrapper wrapper : toProcess) {
              BlockPos pos = wrapper.getPos();
              BlockState state = wrapper.getState();
              if (BlastPlasterUtil.isDynamicTrees(state) && Config.dtSpecialDrops()) { BlastPlasterUtil.addDynamicTreesDropsToPending(pendingRealDrops, serverLevel, pos, state, false); } else {
                state.spawnAfterBreak(serverLevel, pos, ItemStack.EMPTY, indirectIsPlayer);
                BlockEntity be = serverLevel.getBlockEntity(pos);
                LootParams.Builder builder = new LootParams.Builder(serverLevel)
                        .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
                        .withParameter(LootContextParams.TOOL, ItemStack.EMPTY)
                        .withOptionalParameter(LootContextParams.BLOCK_ENTITY, be)
                        .withOptionalParameter(LootContextParams.THIS_ENTITY, exploder)
                        .withParameter(LootContextParams.EXPLOSION_RADIUS, 3.0F);
                for (ItemStack stack : state.getDrops(builder)) {
                  if (!stack.isEmpty()) { pendingRealDrops.add(new BlastPlasterUtil.PendingDrop(Vec3.atCenterOf(pos), stack, false)); }
                }
              }
            }
          }
        }

        for (BlockStatePosWrapper wrapper : toDestroy) {
          BlockPos pos = wrapper.getPos();
          long last = lastProcessedPositions.getOrDefault(pos, 0L);
          if (currentTick - last < 5) { continue; }
          lastProcessedPositions.put(pos, currentTick);

          BlockState state = wrapper.getState();

          if (effectiveMode == ExplosionMode.EJECT_DROPS && !(forcePlayerTNTDrops || isCreeper)) {
            if (BlastPlasterUtil.isDynamicTrees(state) && Config.dtSpecialDrops()) { BlastPlasterUtil.addDynamicTreesDropsToPending(pendingRealDrops, serverLevel, pos, state, false); } else {
              state.spawnAfterBreak(serverLevel, pos, ItemStack.EMPTY, indirectIsPlayer);
              BlockEntity be = serverLevel.getBlockEntity(pos);
              LootParams.Builder builder = new LootParams.Builder(serverLevel)
                      .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
                      .withParameter(LootContextParams.TOOL, ItemStack.EMPTY)
                      .withOptionalParameter(LootContextParams.BLOCK_ENTITY, be)
                      .withOptionalParameter(LootContextParams.THIS_ENTITY, exploder)
                      .withParameter(LootContextParams.EXPLOSION_RADIUS, Math.max(3.0F, (float) Math.sqrt(toProcess.size()) / 2.0F));
              for (ItemStack stack : state.getDrops(builder)) {
                if (!stack.isEmpty()) { pendingRealDrops.add(new BlastPlasterUtil.PendingDrop(Vec3.atCenterOf(pos), stack, false)); }
              }
            }
          }

          float visualChance = BlastPlasterUtil.getVisualSpawnChance(isCreeper, false);
          BlastPlasterUtil.finalizeExplodedBlock(serverLevel, pos, state, effectiveMode, false, visualChance);
        }

        if (!pendingRealDrops.isEmpty()) {
          int nextTick = serverLevel.getServer().getTickCount() + 2;
          serverLevel.getServer().tell(new TickTask(nextTick, () -> {
            for (BlastPlasterUtil.PendingDrop p : pendingRealDrops) {
              ItemEntity item = new ItemEntity(serverLevel, p.pos().x, p.pos().y + 0.5, p.pos().z, p.stack());
              if (p.isGentle()) { BlastPlasterUtil.applyGentleTossVelocity(item, serverLevel); } else { BlastPlasterUtil.applyTossVelocity(item, serverLevel); }
              serverLevel.addFreshEntity(item);
            }
          }));
        }

        if (Config.enableExplosionFlash()) { placeTemporaryLight(serverLevel, BlockPos.containing(explosionCenter), Config.getExplosionFlashLightLevel(), Config.getExplosionFlashDuration()); }
      }
    }
  }

  @SubscribeEvent public void onItemEntityJoin(EntityJoinLevelEvent event) {
    if (event.getLevel().isClientSide) { return; }
    if (!(event.getEntity() instanceof ItemEntity item)) { return; }
    if (BlastPlasterUtil.shouldSuppressItemDrop(item)) {
      event.setCanceled(true);
      BlastPlaster.LOGGER.info("Pre-spawn cancelled stray item drop of {} at {}", item.getItem(), item.position());
    }
  }

  @SubscribeEvent public void onLivingDrops(LivingDropsEvent event) {
    DamageSource source = event.getSource();
    if (!source.is(DamageTypeTags.IS_EXPLOSION)) { return; }
    if (Config.preventMobDrops()) { event.setCanceled(true); return; }
    for (ItemEntity item : event.getDrops()) { item.getPersistentData().putBoolean("BlastPlasterMobDrop", true); }
  }

  private static void spawnExplosionSmoke(ServerLevel level, Vec3 center) {
    int duration = Config.getExplosionSmokeDuration();
    int particleCount = Config.getExplosionSmokeParticleCount();
    int burstInterval = 15;
    int numBursts = Math.max(1, duration / burstInterval);
    int baseTick = level.getServer().getTickCount();

    for (int i = 0; i < numBursts; i++) {
      final int delay = i * burstInterval;
      final int smokeCount = (i == 0) ? particleCount * 2 : particleCount;
      final double yOffset = 0.25 + (i * 0.06);
      level.getServer().tell(new TickTask(baseTick + delay, () -> level.sendParticles(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, center.x, center.y + yOffset, center.z, smokeCount, 1.2, 0.7, 1.2, 0.04)));
    }
  }

  private static void spawnImmediateExplosionVisuals(ServerLevel level, Vec3 center) {
    long currentTick = level.getGameTime();
    if (currentTick - lastFlashTick < 2) { return; }
    lastFlashTick = currentTick;

    int flashCount = Config.getExplosionFlashParticleCount();
    int pulses = Config.getExplosionFlashPulses();
    int baseTick = level.getServer().getTickCount();

    for (int i = 0; i < pulses; i++) {
      final int delay = i * 3;
      final int count = (int) (flashCount * (1.0 - 0.25 * i));
      final double spread = 0.6 + i * 0.4;
      final double yBase = center.y + 0.5 + (i * 0.15);
      final int pulseIndex = i;

      level.getServer().tell(new TickTask(baseTick + delay, () -> {
        level.sendParticles(ParticleTypes.FLASH, center.x, yBase, center.z, count, spread, spread, spread, 0.0);
        if (pulseIndex == 0) {
          level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, center.x, center.y, center.z, 6, 0.0, 0.0, 0.0, 0.0);
          level.sendParticles(ParticleTypes.EXPLOSION, center.x, center.y + 0.3, center.z, 45, 1.0, 1.0, 1.0, 0.0);
        } else { level.sendParticles(ParticleTypes.EXPLOSION, center.x, center.y + 0.3, center.z, 25, 0.6, 0.6, 0.6, 0.0); }
      }));
    }
  }

  private static void placeTemporaryLight(ServerLevel level, BlockPos center, int lightLevel, int duration) {
    if (lightLevel > 0 && duration >= 1) {
      BlockState lightState = Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, lightLevel);
      List<BlockPos> lightPositions = new ArrayList<>();
      for (int dx = -1; dx <= 1; dx++) {
        for (int dy = -1; dy <= 1; dy++) {
          for (int dz = -1; dz <= 1; dz++) {
            if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) <= 2) {
              BlockPos p = center.offset(dx, dy, dz);
              if (level.getBlockState(p).isAir()) { lightPositions.add(p); }
            }
          }
        }
      }
      for (BlockPos p : lightPositions) { level.setBlock(p, lightState, 3); }
      int currentTick = level.getServer().getTickCount();
      level.getServer().tell(new TickTask(currentTick + duration, () -> {
        for (BlockPos p : lightPositions) {
          if (level.getBlockState(p).is(Blocks.LIGHT)) { level.setBlock(p, Blocks.AIR.defaultBlockState(), 3); }
        }
      }));
    }
  }
}
