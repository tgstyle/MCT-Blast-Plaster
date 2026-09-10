package mctmods.blastplaster.handler;

import mctmods.blastplaster.BlastPlaster;
import mctmods.blastplaster.Config;
import mctmods.blastplaster.Config.ExplosionMode;
import mctmods.blastplaster.helper.BlockStatePosWrapper;
import mctmods.blastplaster.util.BlastPlasterUtil;
import mctmods.blastplaster.worldhealer.RegionSnapshotHealer;
import mctmods.blastplaster.worldhealer.WorldHealerSaveDataSupplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import java.util.Iterator;
import org.jetbrains.annotations.Nullable;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.WitherSkull;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ExplosionEventHandler {

  public ExplosionEventHandler() { MinecraftForge.EVENT_BUS.register(this); }

  private static final long MAX_SNAPSHOT_VOLUME = 2_097_152L;
  private static long lastFlashTick = 0;
  private static long lastSweepTick = Long.MIN_VALUE;
  private static final Map<ResourceKey<Level>, Map<Long, Long>> burningLights = new HashMap<>();
  private static final int FLASH_APART = 2;
  private static final int SWEEP_EVERY = 100;
  private static final Map<BlockPos, Long> lastProcessedPositions = new HashMap<>();
  private static final List<PendingEoRegion> pendingEoRegions = new ArrayList<>();

  private record PendingEoRegion(AABB box, long expireTick) {}

  private static Method eoClusterMethod = null;
  private static Method eoCraterMethod = null;
  private static Method eoGetPowerMethod = null;
  private static Class<?> dtFallingTreeClass = null;

  static {
    if (BlastPlasterUtil.EO_LOADED) {
      try {
        Class<?> clusterClass = Class.forName("com.vinlanx.explosionoverhaul.ExplosionClusterHandler");
        eoClusterMethod = clusterClass.getMethod("calculateClusteredPower", net.minecraft.world.level.Level.class, Vec3.class, float.class);

        Class<?> craterClass = Class.forName("com.vinlanx.explosionoverhaul.CraterDeformer");
        eoCraterMethod = craterClass.getMethod("getCraterBlocks", ServerLevel.class, Vec3.class, float.class);

        Class<?> powerInterface = Class.forName("com.vinlanx.explosionoverhaul.api.IExplosionPower");
        eoGetPowerMethod = powerInterface.getMethod("getPower");
      } catch (Exception e) {
        BlastPlaster.LOGGER.warn("[BlastPlaster] EO reflection setup failed - EO crater healing disabled", e);
        eoClusterMethod = null;
        eoCraterMethod = null;
        eoGetPowerMethod = null;
      }
    }
    if (BlastPlasterUtil.DT_LOADED) {
      try { dtFallingTreeClass = Class.forName("com.ferreusveritas.dynamictrees.entity.FallingTreeEntity"); }
      catch (Exception e) { BlastPlaster.LOGGER.warn("[BlastPlaster] DT FallingTreeEntity reflection failed - DT felling suppression disabled", e); }
    }
  }

  @SubscribeEvent(priority = EventPriority.LOWEST)
  public void onDetonate(ExplosionEvent.Detonate event) {
    Level level = event.getLevel();
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

    boolean processThis = Config.view(level).healAll() || (Config.view(level).processPlayerIgnitedTNT() && isPlayerIgnitedTNT);

    if (!processThis) {
      if (Config.view(level).healCreepers() && isCreeper) { processThis = true; }
      if (!processThis && Config.view(level).healWither() && (exploder instanceof WitherBoss || exploder instanceof WitherSkull)) { processThis = true; }
      if (!processThis && Config.view(level).healNonPlayerTNT()) {
        boolean nonPlayerCaused = !(indirect instanceof Player);
        boolean isPrimedTnt = exploder instanceof PrimedTnt;
        boolean isCustomEntity = false;
        for (String idStr : Config.view(level).getCustomEntitiesToHeal()) {
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

    BlastPlaster.debug("Detonate: at {} {} {}, exploder {}, indirect {}, creeper {}, playerTNT {}, process {}, dtLoaded {}, affected {}", (int) explosion.getPosition().x, (int) explosion.getPosition().y, (int) explosion.getPosition().z, exploder == null ? "none" : exploder.getClass().getSimpleName(), indirect == null ? "none" : indirect.getClass().getSimpleName(), isCreeper, isPlayerIgnitedTNT, processThis, BlastPlasterUtil.DT_LOADED, event.getAffectedBlocks().size());

    if (!processThis) { return; }

    ServerLevel serverLevel = (ServerLevel) event.getLevel();
    Vec3 explosionCenter = explosion.getPosition();

    long currentTick = serverLevel.getGameTime();
    if (currentTick - lastSweepTick >= SWEEP_EVERY) {
      lastProcessedPositions.entrySet().removeIf(e -> currentTick - e.getValue() > 600L);
      lastSweepTick = currentTick;
    }

    if (Config.view(level).enableExplosionFlash()) { spawnImmediateExplosionVisuals(serverLevel, explosionCenter); }
    if (Config.view(level).enableExplosionSmoke()) { spawnExplosionSmoke(serverLevel, explosionCenter); }

    ExplosionMode mode = Config.view(level).getExplosionMode();
    ExplosionMode effectiveMode = (isPlayerIgnitedTNT && Config.view(level).playerTNTAlwaysDrops()) ? ExplosionMode.EJECT_DROPS : mode;
    if (Config.view(level).healAll()) { effectiveMode = ExplosionMode.HEAL; }
    WorldHealerSaveDataSupplier worldHealer = (effectiveMode == ExplosionMode.HEAL) ? BlastPlaster.getWorldHealer(serverLevel) : null;

    Map<BlockPos, BlockStatePosWrapper> eoSnapshot = null;
    boolean eoActive = Config.isExplosionOverhaulEnabled() && eoClusterMethod != null && eoCraterMethod != null && eoGetPowerMethod != null;
    if (eoActive) { eoSnapshot = buildEoSnapshot(serverLevel, explosion, explosionCenter, currentTick); }

    boolean forcePlayerTNTDrops = isPlayerIgnitedTNT && Config.view(level).playerTNTAlwaysDrops();
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
      if (Config.view(level).healFullTrees()) {
        WorldHealerSaveDataSupplier expansionHealer = BlastPlaster.getWorldHealer(serverLevel);
        if (expansionHealer != null) { expansionHealer.addExtraTreeBlocks(toProcess, affectedPos, serverLevel); }
      }

      if (effectiveMode != ExplosionMode.EJECT_DROPS) { BlastPlasterUtil.addAttachedCocoaPods(toProcess, affectedPos, serverLevel); }
      if (effectiveMode != ExplosionMode.EJECT_DROPS) { BlastPlasterUtil.addBambooVerticals(toProcess, affectedPos, serverLevel); }

      if (effectiveMode != ExplosionMode.EJECT_DROPS && Config.view(level).enableDropSuppression()) { BlastPlasterUtil.recordExplosionArea(serverLevel, affectedPos, effectiveMode == ExplosionMode.HEAL); }

      explosion.getToBlow().removeAll(affectedPos);

      List<BlockStatePosWrapper> toClear = toProcess;
      if (worldHealer != null && eoSnapshot == null) { toClear = worldHealer.prepareAndScheduleHealing(toProcess, affectedPos, serverLevel); }

      List<BlastPlasterUtil.PendingDrop> pendingRealDrops = new ArrayList<>();

      if (effectiveMode == ExplosionMode.EJECT_DROPS) {
        if (forcePlayerTNTDrops) {
          for (BlockStatePosWrapper wrapper : toProcess) {
            BlockPos pos = wrapper.getPos();
            BlockState state = wrapper.getState();
            if (state.getBlock() == Blocks.TNT) { continue; }
            if (BlastPlasterUtil.isDynamicTrees(state) && Config.view(level).dtSpecialDrops()) { BlastPlasterUtil.addDynamicTreesDropsToPending(pendingRealDrops, serverLevel, pos, state, true); }
            else if (Config.view(level).playerTNTDropFullBlocks()) { pendingRealDrops.add(new BlastPlasterUtil.PendingDrop(Vec3.atCenterOf(pos), new ItemStack(state.getBlock()), true)); }
            else {
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
            if (state.getBlock() == Blocks.TNT) { continue; }
            if (BlastPlasterUtil.isDynamicTrees(state) && Config.view(level).dtSpecialDrops()) { BlastPlasterUtil.addDynamicTreesDropsToPending(pendingRealDrops, serverLevel, pos, state, false); }
            else {
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

      BlastPlasterUtil.setDtDestroyIgnored(true);
      try {
        for (BlockStatePosWrapper wrapper : toClear) {
          BlockPos pos = wrapper.getPos();
          long last = lastProcessedPositions.getOrDefault(pos, 0L);
          if (currentTick - last < 5) { continue; }
          lastProcessedPositions.put(pos, currentTick);

          BlockState state = wrapper.getState();

          if (effectiveMode == ExplosionMode.EJECT_DROPS && !(forcePlayerTNTDrops || isCreeper)) {
            if (state.getBlock() == Blocks.TNT) { continue; }
            if (BlastPlasterUtil.isDynamicTrees(state) && Config.view(level).dtSpecialDrops()) { BlastPlasterUtil.addDynamicTreesDropsToPending(pendingRealDrops, serverLevel, pos, state, false); }
            else {
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
      }
      finally { BlastPlasterUtil.setDtDestroyIgnored(false); }

      if (!pendingRealDrops.isEmpty()) {
        int nextTick = serverLevel.getServer().getTickCount() + 2;
        serverLevel.getServer().tell(new TickTask(nextTick, () -> {
          for (BlastPlasterUtil.PendingDrop p : pendingRealDrops) {
            ItemEntity item = new ItemEntity(serverLevel, p.pos().x, p.pos().y + 0.5, p.pos().z, p.stack());
            BlastPlasterUtil.markSuppressionBypass(item);
            if (p.isGentle()) { BlastPlasterUtil.applyGentleTossVelocity(item, serverLevel); }
            else { BlastPlasterUtil.applyTossVelocity(item, serverLevel); }
            serverLevel.addFreshEntity(item);
          }
        }));
      }

      if (Config.view(level).enableExplosionFlash()) { placeTemporaryLight(serverLevel, BlockPos.containing(explosionCenter), Config.view(level).getExplosionFlashLightLevel(), Config.view(level).getExplosionFlashDuration()); }
    }

    if (eoSnapshot != null && !eoSnapshot.isEmpty()) {
      if (effectiveMode == ExplosionMode.HEAL) {
        for (BlockStatePosWrapper wrapper : toProcess) { eoSnapshot.putIfAbsent(wrapper.getPos(), wrapper); }
      } else {
        for (BlockStatePosWrapper wrapper : toProcess) { eoSnapshot.remove(wrapper.getPos()); }
        eoSnapshot.keySet().removeAll(affectedPos);
      }
      if (eoSnapshot.isEmpty()) { return; }
      if (Config.view(level).enableDropSuppression()) { BlastPlasterUtil.recordExplosionArea(serverLevel, eoSnapshot.keySet(), effectiveMode == ExplosionMode.HEAL); }
      int passes = Math.min(60, 9 + eoSnapshot.size() / 1500);
      RegionSnapshotHealer.scheduleDiffHeal(serverLevel, eoSnapshot, 5, 20, passes, effectiveMode, BlastPlasterUtil.getVisualSpawnChance(isCreeper, false));
      BlastPlaster.debug("[BlastPlaster] EO crater watch: {} blocks tracked (mode {}), {} passes", eoSnapshot.size(), effectiveMode, passes);
    }
  }

  @SuppressWarnings("unchecked")
  private Map<BlockPos, BlockStatePosWrapper> buildEoSnapshot(ServerLevel serverLevel, Explosion explosion, Vec3 center, long currentTick) {
    pendingEoRegions.removeIf(r -> r.expireTick() < currentTick);
    for (PendingEoRegion region : pendingEoRegions) {
      if (region.box().contains(center)) { return null; }
    }

    Set<BlockPos> seeds;
    try {
      float basePower = 4.0F;
      Object power = eoGetPowerMethod.invoke(explosion);
      if (power instanceof Float f) { basePower = f; }
      float clusteredPower = (float) eoClusterMethod.invoke(null, serverLevel, center, basePower);
      seeds = (Set<BlockPos>) eoCraterMethod.invoke(null, serverLevel, center, clusteredPower);
    } catch (Exception e) {
      BlastPlaster.LOGGER.warn("[BlastPlaster] EO crater estimation failed, EO crater tracking skipped for this explosion", e);
      return null;
    }

    if (seeds == null || seeds.isEmpty()) { return null; }

    int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
    int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
    for (BlockPos pos : seeds) {
      minX = Math.min(minX, pos.getX());
      minY = Math.min(minY, pos.getY());
      minZ = Math.min(minZ, pos.getZ());
      maxX = Math.max(maxX, pos.getX());
      maxY = Math.max(maxY, pos.getY());
      maxZ = Math.max(maxZ, pos.getZ());
    }

    int craterSpan = Math.max(maxX - minX, maxZ - minZ);
    int lateralMargin = Mth.clamp(craterSpan / 4, 6, 16);
    int topMargin = Mth.clamp(craterSpan / 2, 16, 32);
    int bottomMargin = 4;

    minX -= lateralMargin; minZ -= lateralMargin; maxX += lateralMargin; maxZ += lateralMargin;
    minY = Mth.clamp(minY - bottomMargin, serverLevel.getMinBuildHeight(), serverLevel.getMaxBuildHeight() - 1);
    maxY = Mth.clamp(maxY + topMargin, serverLevel.getMinBuildHeight(), serverLevel.getMaxBuildHeight() - 1);

    long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
    if (volume > MAX_SNAPSHOT_VOLUME) {
      BlastPlaster.LOGGER.warn("[BlastPlaster] EO explosion region too large to snapshot ({} blocks), EO crater tracking skipped", volume);
      return null;
    }

    Map<BlockPos, BlockStatePosWrapper> snapshot = new HashMap<>();
    BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    for (int x = minX; x <= maxX; x++) {
      for (int y = minY; y <= maxY; y++) {
        for (int z = minZ; z <= maxZ; z++) {
          cursor.set(x, y, z);
          BlockState state = serverLevel.getBlockState(cursor);
          if (state.isAir()) { continue; }
          if (state.getBlock() == Blocks.TNT) { continue; }
          BlockPos immutable = cursor.immutable();
          snapshot.put(immutable, new BlockStatePosWrapper(serverLevel, immutable, state));
        }
      }
    }

    if (snapshot.isEmpty()) { return null; }
    pendingEoRegions.add(new PendingEoRegion(new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1), currentTick + 200L));
    return snapshot;
  }

  @SubscribeEvent public void onEntityJoinLevel(EntityJoinLevelEvent event) {
    if (event.getLevel().isClientSide) { return; }

    if (event.getEntity() instanceof ItemEntity item) {
      if (BlastPlasterUtil.shouldSuppressItemDrop(item)) {
        event.setCanceled(true);
      }
      return;
    }

    if (event.getEntity() instanceof FallingBlockEntity falling) {
      if (BlastPlasterUtil.shouldSuppressFallingBlock(falling)) {
        event.setCanceled(true);
      }
      return;
    }

    if (dtFallingTreeClass != null && dtFallingTreeClass.isInstance(event.getEntity())) {
      if (event.getLevel() instanceof ServerLevel serverLevel && BlastPlasterUtil.shouldSuppressLaunchAt(serverLevel, event.getEntity().position())) {
        event.setCanceled(true);
      }
    }
  }

  @SubscribeEvent public void onLivingDrops(LivingDropsEvent event) {
    Level level = event.getEntity().level();
    DamageSource source = event.getSource();
    if (!source.is(DamageTypeTags.IS_EXPLOSION)) { return; }
    if (Config.view(level).preventMobDrops()) { event.setCanceled(true); return; }
    for (ItemEntity item : event.getDrops()) { item.getPersistentData().putBoolean("BlastPlasterMobDrop", true); }
  }

  private static void spawnExplosionSmoke(ServerLevel level, Vec3 center) {
    int duration = Config.view(level).getExplosionSmokeDuration();
    int particleCount = Config.view(level).getExplosionSmokeParticleCount();
    int burstInterval = 15;
    final int numBursts = Math.max(1, duration / burstInterval);
    BlastPlaster.debug("Smoke: count {}, duration {}, {} bursts scheduled from tick {}", particleCount, duration, numBursts, level.getServer().getTickCount());
    int baseTick = level.getServer().getTickCount();

    for (int i = 0; i < numBursts; i++) {
      final int delay = i * burstInterval;
      final int smokeCount = (i == 0) ? particleCount * 2 : particleCount;
      final double yOffset = 0.25 + (i * 0.06);
      final int burstIndex = i;
      level.getServer().tell(new TickTask(baseTick + delay, () -> {
        BlastPlaster.debug("Smoke burst {} of {}: {} particles at server tick {}", burstIndex + 1, numBursts, smokeCount, level.getServer().getTickCount());
        level.sendParticles(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, center.x, center.y + yOffset, center.z, smokeCount, 1.2, 0.7, 1.2, 0.04);
      }));
    }
  }

  private static void spawnImmediateExplosionVisuals(ServerLevel level, Vec3 center) {
    long currentTick = level.getGameTime();
    if (currentTick - lastFlashTick < 2) { return; }
    lastFlashTick = currentTick;

    int flashCount = Config.view(level).getExplosionFlashParticleCount();
    int pulses = Config.view(level).getExplosionFlashPulses();
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
          level.sendParticles(ParticleTypes.EXPLOSION, center.x, center.y + 0.3, center.z, 45, 0.0, 1.0, 1.0, 0.0);
        } else { level.sendParticles(ParticleTypes.EXPLOSION, center.x, center.y + 0.3, center.z, 25, 0.6, 0.6, 0.6, 0.0); }
      }));
    }
  }

  private static void placeTemporaryLight(ServerLevel level, BlockPos center, int lightLevel, int duration) {
    if (lightLevel <= 0 || duration < 1) { return; }
    BlockPos spot = airSpot(level, center);
    if (spot == null) { return; }
    long now = level.getGameTime();
    Map<Long, Long> burning = burningLights.computeIfAbsent(level.dimension(), k -> new HashMap<>());
    if (flashNear(burning, spot, now)) { return; }
    long key = spot.asLong();
    burning.put(key, now + duration);
    level.setBlock(spot, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, lightLevel), Block.UPDATE_CLIENTS);
    int currentTick = level.getServer().getTickCount();
    level.getServer().tell(new TickTask(currentTick + duration, () -> {
      burning.remove(key);
      if (level.getBlockState(spot).is(Blocks.LIGHT)) { level.setBlock(spot, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS); }
    }));
  }

  @Nullable private static BlockPos airSpot(ServerLevel level, BlockPos center) {
    if (level.getBlockState(center).isAir()) { return center; }
    for (Direction side : Direction.values()) {
      BlockPos beside = center.relative(side);
      if (level.getBlockState(beside).isAir()) { return beside; }
    }
    return null;
  }

  private static boolean flashNear(Map<Long, Long> burning, BlockPos spot, long now) {
    Iterator<Map.Entry<Long, Long>> it = burning.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry<Long, Long> held = it.next();
      if (held.getValue() <= now) {
        it.remove();
        continue;
      }
      BlockPos at = BlockPos.of(held.getKey());
      if (Math.abs(at.getX() - spot.getX()) <= FLASH_APART && Math.abs(at.getY() - spot.getY()) <= FLASH_APART && Math.abs(at.getZ() - spot.getZ()) <= FLASH_APART) { return true; }
    }
    return false;
  }
}
