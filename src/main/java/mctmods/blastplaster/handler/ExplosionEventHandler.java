package mctmods.blastplaster.handler;

import mctmods.blastplaster.BlastPlaster;
import mctmods.blastplaster.Config;
import mctmods.blastplaster.Config.ExplosionMode;
import mctmods.blastplaster.helper.BlockStatePosWrapper;
import mctmods.blastplaster.util.BlastPlasterUtil;
import mctmods.blastplaster.util.BlockConversions;
import mctmods.blastplaster.worldhealer.WorldHealerSaveDataSupplier;

import net.minecraft.core.BlockPos;
import java.util.Iterator;
import org.jetbrains.annotations.Nullable;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.HugeMushroomBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;

import com.dtteam.dynamictrees.api.network.MapSignal;
import com.dtteam.dynamictrees.block.branch.BasicRootsBlock;
import com.dtteam.dynamictrees.tree.TreeHelper;
import com.dtteam.dynamictrees.block.branch.SurfaceRootBlock;
import com.dtteam.dynamictrees.block.branch.TrunkShellBlock;
import com.dtteam.dynamictrees.block.fruit.FruitBlock;
import com.dtteam.dynamictrees.block.pod.PodBlock;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


public class ExplosionEventHandler {

  public ExplosionEventHandler() { NeoForge.EVENT_BUS.register(this); }

  private static long lastFlashTick = 0;
  private static long lastSweepTick = Long.MIN_VALUE;
  private static final Map<ResourceKey<Level>, Map<Long, Long>> burningLights = new HashMap<>();
  private static final int FLASH_APART = 2;
  private static final int SWEEP_EVERY = 100;
  private static final int PUNCH_WINDOW = 5;
  private static final String PLAYER_LIT_TAG = "BlastPlasterPlayerLit";
  private static final Map<ResourceKey<Level>, Map<Long, Long>> recentTntPunches = new HashMap<>();
  private static final Map<BlockPos, Long> lastProcessedPositions = new HashMap<>();

  @SubscribeEvent public void onDetonate(ExplosionEvent.Detonate event) {
    Level level = event.getLevel();
    if (level.isClientSide()) { return; }

    Explosion explosion = event.getExplosion();
    Entity exploder = explosion.getDirectSourceEntity();
    LivingEntity indirect = explosion.getIndirectSourceEntity();

    boolean isPlayerIgnitedTNT = false;
    if (exploder instanceof PrimedTnt primed) {
      LivingEntity owner = primed.getOwner();
      isPlayerIgnitedTNT = (owner instanceof Player) || (indirect instanceof Player) || primed.getPersistentData().getBoolean(PLAYER_LIT_TAG).orElse(false);
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
          Identifier identifier = Identifier.tryParse(idStr);
          if (identifier != null) {
            ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, identifier);
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(key).orElse(null);
            if (type != null && ((exploder != null && exploder.getType() == type) || (indirect != null && indirect.getType() == type))) { isCustomEntity = true; break; }
          }
        }
        if (nonPlayerCaused && (isPrimedTnt || isCustomEntity)) { processThis = true; }
      }
    }

    BlastPlaster.debug("Detonate: at {} {} {}, exploder {}, indirect {}, creeper {}, playerTNT {}, process {}, dtLoaded {}, affected {}", (int) explosion.center().x, (int) explosion.center().y, (int) explosion.center().z, exploder == null ? "none" : exploder.getClass().getSimpleName(), indirect == null ? "none" : indirect.getClass().getSimpleName(), isCreeper, isPlayerIgnitedTNT, processThis, BlastPlasterUtil.DT_LOADED, event.getAffectedBlocks().size());

    if (processThis) {
      ServerLevel serverLevel = (ServerLevel) event.getLevel();
      Vec3 explosionCenter = explosion.center();

      long currentTick = serverLevel.getGameTime();
      if (currentTick - lastSweepTick >= SWEEP_EVERY) {
        lastProcessedPositions.entrySet().removeIf(e -> currentTick - e.getValue() > 600L);
        lastSweepTick = currentTick;
      }

      if (Config.view(level).enableExplosionFlash()) { spawnImmediateExplosionVisuals(serverLevel, explosionCenter); }
      if (Config.view(level).enableExplosionSmoke()) { spawnExplosionSmoke(serverLevel, explosionCenter); }

      ExplosionMode mode = Config.view(level).getExplosionMode();
      ExplosionMode effectiveMode = (isPlayerIgnitedTNT && Config.view(level).playerTNTAlwaysDrops()) ? ExplosionMode.EJECT_DROPS : mode;
      WorldHealerSaveDataSupplier worldHealer = (effectiveMode == ExplosionMode.HEAL) ? BlastPlaster.getWorldHealer(serverLevel) : null;

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

      if (Config.view(level).healFullTrees()) {
        addFullTreeExpansion(toProcess, affectedPos, serverLevel);
      }

      if (worldHealer != null) {
        worldHealer.addMultiBlockStructures(toProcess, affectedPos, serverLevel);
      }

      if (effectiveMode != ExplosionMode.EJECT_DROPS) {
        BlastPlasterUtil.addAttachedCocoaPods(toProcess, affectedPos, serverLevel);
        BlastPlasterUtil.addBambooVerticals(toProcess, affectedPos, serverLevel);
      }

      BlockConversions.applyAll(serverLevel, toProcess);

      if (Config.view(level).enableDropSuppression()) {
        BlastPlasterUtil.recordExplosionArea(serverLevel, affectedPos);
      }

      List<BlockStatePosWrapper> fullToProcessForDestroy = new ArrayList<>(toProcess);

      if (worldHealer != null) {
        worldHealer.prepareAndScheduleHealing(toProcess);
      }

      List<BlastPlasterUtil.PendingDrop> pendingRealDrops = new ArrayList<>();

      if (effectiveMode == ExplosionMode.EJECT_DROPS) {
        if (forcePlayerTNTDrops) {
          for (BlockStatePosWrapper wrapper : toProcess) {
            BlockPos pos = wrapper.getPos();
            BlockState state = wrapper.getState();
            if (BlastPlasterUtil.isDynamicTrees(state) && Config.view(level).dtSpecialDrops()) {
              BlastPlasterUtil.addDynamicTreesDropsToPending(pendingRealDrops, serverLevel, pos, state, true);
            } else if (Config.view(level).playerTNTDropFullBlocks()) {
              pendingRealDrops.add(new BlastPlasterUtil.PendingDrop(Vec3.atCenterOf(pos), new ItemStack(state.getBlock()), true));
            } else {
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
            if (BlastPlasterUtil.isDynamicTrees(state) && Config.view(level).dtSpecialDrops()) {
              BlastPlasterUtil.addDynamicTreesDropsToPending(pendingRealDrops, serverLevel, pos, state, false);
            } else {
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
        for (BlockStatePosWrapper wrapper : fullToProcessForDestroy) {
          BlockPos pos = wrapper.getPos();
          long last = lastProcessedPositions.getOrDefault(pos, 0L);
          if (currentTick - last < 5) { continue; }
          lastProcessedPositions.put(pos, currentTick);

          BlockState state = wrapper.getState();

          if (effectiveMode == ExplosionMode.EJECT_DROPS && !(forcePlayerTNTDrops || isCreeper)) {
            if (BlastPlasterUtil.isDynamicTrees(state) && Config.view(level).dtSpecialDrops()) {
              BlastPlasterUtil.addDynamicTreesDropsToPending(pendingRealDrops, serverLevel, pos, state, false);
            } else {
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

          float visualChance = BlastPlasterUtil.getVisualSpawnChance(isCreeper);
          boolean realDropOccurred = (effectiveMode == ExplosionMode.EJECT_DROPS);
          BlastPlasterUtil.finalizeExplodedBlock(serverLevel, pos, state, effectiveMode, realDropOccurred, visualChance);
        }
      }
      finally { BlastPlasterUtil.setDtDestroyIgnored(false); }

      if (!pendingRealDrops.isEmpty()) {
        serverLevel.getServer().execute(() -> {
          for (BlastPlasterUtil.PendingDrop p : pendingRealDrops) {
            ItemEntity item = new ItemEntity(serverLevel, p.pos().x, p.pos().y + 0.5, p.pos().z, p.stack());
            BlastPlasterUtil.markSuppressionBypass(item);
            if (p.isGentle()) { BlastPlasterUtil.applyGentleTossVelocity(item, serverLevel); } else { BlastPlasterUtil.applyTossVelocity(item, serverLevel); }
            serverLevel.addFreshEntity(item);
          }
        });
      }

      if (Config.view(level).enableExplosionFlash()) { placeTemporaryLight(serverLevel, BlockPos.containing(explosionCenter), Config.view(level).getExplosionFlashLightLevel(), Config.view(level).getExplosionFlashDuration()); }
    }
  }

  private void addFullTreeExpansion(List<BlockStatePosWrapper> toProcess, Set<BlockPos> affectedPos, ServerLevel level) {
    boolean dtLoaded = BlastPlasterUtil.DT_LOADED;
    boolean didDt = false;

    if (dtLoaded) {
      didDt = doDynamicTreesExpansion(toProcess, affectedPos, level);
    }

    if (Config.view(level).healFullTrees()) {
      doVanillaTreeExpansion(toProcess, affectedPos, level);
    }

    addHugeMushrooms(toProcess, affectedPos, level);

    if (dtLoaded && didDt) {
      BlastPlaster.LOGGER.info("[BlastPlaster] DT expansion completed successfully");
    }
  }

  private void addHugeMushrooms(List<BlockStatePosWrapper> toHeal, Set<BlockPos> affectedPos, ServerLevel level) {
    Deque<BlockPos> queue = new ArrayDeque<>();
    Set<BlockPos> visited = new HashSet<>();
    for (BlockStatePosWrapper w : new ArrayList<>(toHeal)) {
      if (w.getState().getBlock() instanceof HugeMushroomBlock) {
        if (visited.add(w.getPos())) { queue.add(w.getPos()); }
      }
    }
    if (queue.isEmpty()) { return; }

    Set<BlockPos> extras = new HashSet<>();
    int cap = Config.view(level).getMaxTreeSize();
    while (!queue.isEmpty() && extras.size() < cap) {
      BlockPos pos = queue.poll();
      for (BlockPos side : BlastPlasterUtil.NEIGHBOR_POSITIONS) {
        BlockPos adj = pos.offset(side);
        if (!visited.add(adj)) { continue; }
        if (affectedPos.contains(adj)) { continue; }
        if (level.getBlockState(adj).getBlock() instanceof HugeMushroomBlock) {
          extras.add(adj);
          queue.add(adj);
        }
      }
    }

    if (!extras.isEmpty()) { BlastPlaster.debug("Huge mushroom expansion added {} blocks", extras.size()); }
    for (BlockPos p : extras) {
      if (!affectedPos.contains(p)) {
        toHeal.add(new BlockStatePosWrapper(level, p, level.getBlockState(p)));
        affectedPos.add(p);
      }
    }
  }

  private boolean doDynamicTreesExpansion(List<BlockStatePosWrapper> toProcess, Set<BlockPos> affectedPos, ServerLevel level) {
    Set<BlockPos> dtTreePos = new HashSet<>();
    Set<BlockPos> uniqueRoots = new HashSet<>();

    for (BlockPos pos : new HashSet<>(affectedPos)) {
      BlockState state = level.getBlockState(pos);
      if (TreeHelper.isBranch(state) || TreeHelper.isRooty(state) || state.getBlock() instanceof BasicRootsBlock) {
        BlockPos rootPos = TreeHelper.findRootNode(level, pos);
        if (rootPos != BlockPos.ZERO) {
          uniqueRoots.add(rootPos.immutable());
        }
      }
      else if (state.getBlock() instanceof TrunkShellBlock || state.getBlock() instanceof SurfaceRootBlock) {
        for (int dx = -1; dx <= 1; dx++) {
          for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) { continue; }
            if (TreeHelper.isBranch(level.getBlockState(pos.offset(dx, 0, dz)))) {
              BlockPos rootPos = TreeHelper.findRootNode(level, pos.offset(dx, 0, dz));
              if (rootPos != BlockPos.ZERO) { uniqueRoots.add(rootPos.immutable()); }
            }
          }
        }
      }
      if (TreeHelper.isLeaves(state)) {
        dtTreePos.add(pos.immutable());
      }
    }

    for (BlockPos rootPos : new HashSet<>(uniqueRoots)) {
      CollectorNode collector = new CollectorNode(dtTreePos);
      try {
        TreeHelper.startAnalysisFromRoot(level, rootPos, new MapSignal(collector));
      } catch (Exception e) {
        BlastPlaster.LOGGER.warn("[BlastPlaster] DT analysis error at root {}: {}", rootPos, e.toString());
      }
    }

    for (BlockPos rp : new HashSet<>(uniqueRoots)) {
      if (!dtTreePos.contains(rp)) {
        BlockState rs = level.getBlockState(rp);
        if (TreeHelper.isRooty(rs) || rs.getBlock() instanceof BasicRootsBlock) {
          dtTreePos.add(rp.immutable());
        }
      }
    }

    for (BlockPos rp : new HashSet<>(uniqueRoots)) {
      BlockState rs = level.getBlockState(rp);
      if (rs.getBlock() instanceof BasicRootsBlock || TreeHelper.isRooty(rs)) {
        collectConnectedRoots(rp, dtTreePos, level);
      }
    }

    Set<BlockPos> fillBranches = new HashSet<>();
    for (BlockPos p : new HashSet<>(dtTreePos)) {
      for (BlockPos offset : BlastPlasterUtil.NEIGHBOR_POSITIONS) {
        BlockPos adj = p.offset(offset);
        if (!dtTreePos.contains(adj) && !affectedPos.contains(adj)) {
          BlockState s = level.getBlockState(adj);
          if (TreeHelper.isBranch(s)) {
            fillBranches.add(adj.immutable());
          }
        }
      }
    }
    dtTreePos.addAll(fillBranches);

    Set<BlockPos> shellRing = new HashSet<>();
    for (BlockPos p : new HashSet<>(dtTreePos)) {
      for (int dx = -1; dx <= 1; dx++) {
        for (int dz = -1; dz <= 1; dz++) {
          if (dx == 0 && dz == 0) { continue; }
          BlockPos adj = p.offset(dx, 0, dz);
          if (dtTreePos.contains(adj)) { continue; }
          if (level.getBlockState(adj).getBlock() instanceof TrunkShellBlock) { shellRing.add(adj.immutable()); }
        }
      }
    }
    dtTreePos.addAll(shellRing);

    Set<BlockPos> surfaceRoots = new HashSet<>();
    Deque<BlockPos> srQueue = new ArrayDeque<>(dtTreePos);
    Set<BlockPos> srVisited = new HashSet<>(dtTreePos);
    while (!srQueue.isEmpty() && surfaceRoots.size() < Config.view(level).getMaxTreeSize()) {
      BlockPos current = srQueue.poll();
      if (current == null) { break; }
      for (BlockPos offset : BlastPlasterUtil.NEIGHBOR_POSITIONS) {
        BlockPos adj = current.offset(offset);
        if (!srVisited.add(adj)) { continue; }
        if (level.getBlockState(adj).getBlock() instanceof SurfaceRootBlock) {
          surfaceRoots.add(adj.immutable());
          srQueue.add(adj);
        }
      }
    }
    dtTreePos.addAll(surfaceRoots);

    Set<BlockPos> extraDTLeaves = new HashSet<>();
    for (BlockPos branch : new HashSet<>(dtTreePos)) {
      for (int dx = -5; dx <= 5; dx++) {
        for (int dy = -5; dy <= 5; dy++) {
          for (int dz = -5; dz <= 5; dz++) {
            if (dx == 0 && dy == 0 && dz == 0) continue;
            BlockPos candidate = branch.offset(dx, dy, dz);
            if (dtTreePos.contains(candidate) || affectedPos.contains(candidate)) continue;
            BlockState s = level.getBlockState(candidate);
            if (TreeHelper.isLeaves(s) || s.getBlock() instanceof FruitBlock || s.getBlock() instanceof PodBlock) {
              extraDTLeaves.add(candidate.immutable());
            }
          }
        }
      }
    }
    dtTreePos.addAll(extraDTLeaves);

    if (dtTreePos.size() > Config.view(level).getMaxTreeSize()) {
      BlastPlaster.LOGGER.info("[BlastPlaster] Skipped huge DT tree ({} > max)", dtTreePos.size());
      return false;
    }

    int added = 0;
    for (BlockPos p : dtTreePos) {
      if (!affectedPos.contains(p)) {
        BlockState state = level.getBlockState(p);
        toProcess.add(new BlockStatePosWrapper(level, p, state));
        affectedPos.add(p);
        added++;
      }
    }
    BlastPlaster.LOGGER.info("[BlastPlaster] DT added {} new blocks", added);
    return added > 0 || !uniqueRoots.isEmpty();
  }

  private record CollectorNode(Set<BlockPos> nodeSet) implements com.dtteam.dynamictrees.api.network.NodeInspector {
    @Override public boolean run(BlockState state, LevelAccessor level, BlockPos pos, Direction fromDir) {
      nodeSet.add(pos.immutable());
      return true;
    }
    @Override public boolean returnRun(BlockState state, LevelAccessor level, BlockPos pos, Direction fromDir) { return false; }
  }

  private void collectConnectedRoots(BlockPos start, Set<BlockPos> dtTreePos, ServerLevel level) {
    Set<BlockPos> visited = new HashSet<>();
    Deque<BlockPos> queue = new ArrayDeque<>();
    queue.add(start);
    visited.add(start);
    while (!queue.isEmpty()) {
      BlockPos current = queue.poll();
      for (BlockPos offset : BlastPlasterUtil.NEIGHBOR_POSITIONS) {
        BlockPos adj = current.offset(offset);
        if (visited.contains(adj)) continue;
        BlockState s = level.getBlockState(adj);
        if (s.getBlock() instanceof BasicRootsBlock || s.getBlock() instanceof SurfaceRootBlock || TreeHelper.isRooty(s)) {
          visited.add(adj);
          dtTreePos.add(adj.immutable());
          queue.add(adj);
        }
      }
    }
  }

  private void doVanillaTreeExpansion(List<BlockStatePosWrapper> toHeal, Set<BlockPos> affectedPos, Level level) {
    Set<TagKey<Block>> logTagsFound = new HashSet<>();
    for (BlockStatePosWrapper w : toHeal) {
      BlockState s = w.getState();
      if (s.is(BlockTags.LOGS)) {
        for (TagKey<Block> tag : Config.getTreeMap().keySet()) {
          if (s.is(tag)) {
            logTagsFound.add(tag);
            break;
          }
        }
      }
    }

    for (TagKey<Block> logTag : logTagsFound) {
      Block leafBlock = Config.getTreeMap().get(logTag);
      if (leafBlock == null) continue;

      Set<BlockPos> logSeeds = new HashSet<>();
      for (BlockStatePosWrapper w : toHeal) {
        if (w.getState().is(logTag)) { logSeeds.add(w.getPos()); }
      }
      if (logSeeds.isEmpty()) continue;

      List<Set<BlockPos>> trunkClusters = findConnectedLogClusters(logSeeds);

      for (Set<BlockPos> cluster : trunkClusters) {
        int originalSize = cluster.size();
        Set<BlockPos> seedSet = cluster;
        if (originalSize == 1) { seedSet = augmentVertical(cluster, level, logTag); }
        int clusterSize = seedSet.size();

        if (clusterSize < 2 && originalSize != 1) continue;

        Set<BlockPos> allLogs = new HashSet<>(seedSet);
        Set<BlockPos> extraLeaves = new HashSet<>();
        Set<BlockPos> visited = new HashSet<>(seedSet);
        Deque<BlockPos> openSet = new ArrayDeque<>(seedSet);
        Map<BlockPos, Integer> distanceMap = new HashMap<>();
        for (BlockPos seed : seedSet) { distanceMap.put(seed, 0); }

        while (!openSet.isEmpty()) {
          BlockPos pos = openSet.poll();
          int dist = distanceMap.get(pos);
          for (BlockPos side : BlastPlasterUtil.NEIGHBOR_POSITIONS) {
            BlockPos adj = pos.offset(side);
            if (visited.contains(adj)) continue;
            BlockState adjState = level.getBlockState(adj);
            int newDist = dist + 1;
            if (newDist > 60) continue;

            if (adjState.is(logTag)) {
              visited.add(adj);
              openSet.add(adj);
              allLogs.add(adj);
              distanceMap.put(adj, newDist);
            } else if (adjState.getBlock() == leafBlock) {
              if (getLeafDistance(adjState) <= 7) {
                visited.add(adj);
                extraLeaves.add(adj);
                distanceMap.put(adj, newDist);
              }
            }
          }
        }

        for (BlockPos logPos : allLogs) {
          for (int dx = -5; dx <= 5; dx++) {
            for (int dy = -5; dy <= 5; dy++) {
              for (int dz = -5; dz <= 5; dz++) {
                if (dx == 0 && dy == 0 && dz == 0) continue;
                BlockPos adj = logPos.offset(dx, dy, dz);
                if (visited.contains(adj)) continue;
                BlockState adjState = level.getBlockState(adj);
                if (adjState.getBlock() == leafBlock && getLeafDistance(adjState) <= 7) {
                  visited.add(adj);
                  extraLeaves.add(adj);
                }
              }
            }
          }
        }

        Set<BlockPos> filteredLeaves = new HashSet<>();
        for (BlockPos leaf : extraLeaves) {
          int dOur = minManhattanToSet(leaf, allLogs);
          int dForeign = minDistToForeignLogs(leaf, level, logTag, allLogs);
          if (dOur <= dForeign) { filteredLeaves.add(leaf); }
        }
        extraLeaves = filteredLeaves;

        int extraLeafCount = extraLeaves.size();
        float leafLogRatio = allLogs.isEmpty() ? 0f : (float)extraLeafCount / allLogs.size();
        float confidence = calculateTreeConfidence(allLogs, extraLeaves, level);

        if (allLogs.size() + extraLeaves.size() > Config.view(level).getMaxTreeSize() ||
                extraLeafCount < Math.max(4, (int)(clusterSize * 0.8f)) ||
                leafLogRatio < 0.4f ||
                confidence < 0.65f ||
                isHollowStructure(allLogs, level)) {
          continue;
        }

        for (BlockPos p : allLogs) {
          if (!affectedPos.contains(p)) {
            BlockState state = level.getBlockState(p);
            toHeal.add(new BlockStatePosWrapper(level, p, state));
            affectedPos.add(p);
          }
        }
        for (BlockPos p : extraLeaves) {
          if (!affectedPos.contains(p)) {
            BlockState state = level.getBlockState(p);
            toHeal.add(new BlockStatePosWrapper(level, p, state));
            affectedPos.add(p);
          }
        }
      }
    }

    if (Config.view(level).healFullTrees()) {
      addConnectedVines(toHeal, affectedPos, level);
    }
  }

  private List<Set<BlockPos>> findConnectedLogClusters(Set<BlockPos> seeds) {
    List<Set<BlockPos>> clusters = new ArrayList<>();
    Set<BlockPos> visited = new HashSet<>();
    for (BlockPos seed : new ArrayList<>(seeds)) {
      if (visited.contains(seed)) continue;
      Set<BlockPos> cluster = new HashSet<>();
      Deque<BlockPos> queue = new ArrayDeque<>();
      queue.add(seed);
      visited.add(seed);
      cluster.add(seed);
      while (!queue.isEmpty()) {
        BlockPos pos = queue.poll();
        for (BlockPos side : BlastPlasterUtil.NEIGHBOR_POSITIONS) {
          BlockPos adj = pos.offset(side);
          if (!visited.contains(adj) && seeds.contains(adj)) {
            visited.add(adj);
            queue.add(adj);
            cluster.add(adj);
          }
        }
      }
      clusters.add(cluster);
    }
    return clusters;
  }

  private Set<BlockPos> augmentVertical(Set<BlockPos> cluster, Level level, TagKey<Block> logTag) {
    if (cluster.size() != 1) return cluster;
    BlockPos start = cluster.iterator().next();
    Set<BlockPos> vertical = new HashSet<>();
    vertical.add(start);
    BlockPos p = start;
    while (true) {
      BlockPos next = p.above();
      if (!level.getBlockState(next).is(logTag)) break;
      vertical.add(next);
      p = next;
      if (vertical.size() > 60) break;
    }
    p = start;
    while (true) {
      BlockPos next = p.below();
      if (!level.getBlockState(next).is(logTag)) break;
      vertical.add(next);
      p = next;
      if (vertical.size() > 60) break;
    }
    return vertical;
  }

  private float calculateTreeConfidence(Set<BlockPos> allLogs, Set<BlockPos> extraLeaves, Level level) {
    if (allLogs.isEmpty()) return 0f;
    int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
    for (BlockPos p : allLogs) { int y = p.getY(); minY = Math.min(minY, y); maxY = Math.max(maxY, y); }
    int height = maxY - minY + 1;
    if (height < 4) return 0.3f;

    boolean hasGrounding = false;
    for (BlockPos log : allLogs) {
      if (log.getY() == minY) {
        BlockPos below = log.below();
        BlockState bs = level.getBlockState(below);
        if (bs.is(BlockTags.DIRT) || bs.getBlock() == Blocks.GRASS_BLOCK || bs.getBlock() == Blocks.PODZOL || bs.getBlock() == Blocks.MYCELIUM) { hasGrounding = true; break; }
      }
    }

    int upperYThreshold = maxY - (int)(height * 0.4);
    int upperLeaves = 0;
    for (BlockPos leaf : extraLeaves) { if (leaf.getY() >= upperYThreshold) upperLeaves++; }
    float verticalBias = extraLeaves.isEmpty() ? 0f : (float)upperLeaves / extraLeaves.size();

    int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MAX_VALUE;
    for (BlockPos p : allLogs) { minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX()); minZ = Math.min(minZ, p.getZ()); }
    int width = Math.max(maxX - minX + 1, maxZ - minZ + 1);
    float aspect = height / (float)Math.max(width, 1);
    boolean isPure = !hasArtificialStructuresNearby(allLogs, level);

    float confidence = 0.35f;
    if (hasGrounding) confidence += 0.25f;
    if (verticalBias >= 0.55f) confidence += 0.2f;
    if (aspect >= 1.55f) confidence += 0.15f;
    if (isPure) confidence += 0.2f;
    return Math.min(1.0f, confidence);
  }

  private boolean hasArtificialStructuresNearby(Set<BlockPos> allLogs, Level level) {
    for (BlockPos log : allLogs) {
      for (int dx = -4; dx <= 4; dx++) for (int dy = -4; dy <= 4; dy++) for (int dz = -4; dz <= 4; dz++) {
        BlockPos p = log.offset(dx, dy, dz);
        BlockState state = level.getBlockState(p);
        if (state.is(BlockTags.PLANKS) || state.is(BlockTags.WOODEN_FENCES) || state.is(BlockTags.WOODEN_STAIRS) || state.is(BlockTags.WOODEN_SLABS)) return true;
      }
    }
    return false;
  }

  private int minManhattanToSet(BlockPos target, Set<BlockPos> set) {
    int min = Integer.MAX_VALUE;
    for (BlockPos p : set) min = Math.min(min, Math.abs(target.getX() - p.getX()) + Math.abs(target.getY() - p.getY()) + Math.abs(target.getZ() - p.getZ()));
    return min;
  }

  private int minDistToForeignLogs(BlockPos leaf, Level level, TagKey<Block> logTag, Set<BlockPos> ourLogs) {
    int min = Integer.MAX_VALUE;
    for (int dx = -8; dx <= 8; dx++) for (int dy = -8; dy <= 8; dy++) for (int dz = -8; dz <= 8; dz++) {
      BlockPos p = leaf.offset(dx, dy, dz);
      if (ourLogs.contains(p)) continue;
      if (level.getBlockState(p).is(logTag)) {
        int d = Math.abs(leaf.getX() - p.getX()) + Math.abs(leaf.getY() - p.getY()) + Math.abs(leaf.getZ() - p.getZ());
        if (d < min) min = d;
      }
    }
    return min == Integer.MAX_VALUE ? 999 : min;
  }

  private boolean isHollowStructure(Set<BlockPos> allLogs, Level level) {
    Map<Integer, List<BlockPos>> logsByY = new HashMap<>();
    for (BlockPos p : allLogs) logsByY.computeIfAbsent(p.getY(), ignored -> new ArrayList<>()).add(p);

    BlastPlaster.debug("Hollow check v3: {} logs across {} slices", allLogs.size(), logsByY.size());
    Map<Integer, Set<Long>> enclosedByY = new HashMap<>();
    for (Map.Entry<Integer, List<BlockPos>> entry : logsByY.entrySet()) {
      List<BlockPos> slice = entry.getValue();
      if (slice.size() < 9) { continue; }
      int cluster = countMaxHorizontalCluster(slice);
      if (cluster < 7) { continue; }
      Set<Long> enclosed = enclosedCells(slice);
      BlastPlaster.debug("Hollow check v3: slice y {} size {} cluster {} enclosed {}", entry.getKey(), slice.size(), cluster, enclosed.size());
      if (!enclosed.isEmpty()) { enclosedByY.put(entry.getKey(), enclosed); }
    }

    Map<Long, Integer> columnTops = new HashMap<>();
    for (Map.Entry<Integer, Set<Long>> entry : enclosedByY.entrySet()) {
      Set<Long> above = enclosedByY.get(entry.getKey() + 1);
      if (above == null) { continue; }
      for (Long cell : entry.getValue()) {
        if (!above.contains(cell)) { continue; }
        Integer top = columnTops.get(cell);
        if (top == null || top < entry.getKey() + 1) { columnTops.put(cell, entry.getKey() + 1); }
      }
    }
    if (columnTops.isEmpty()) {
      BlastPlaster.debug("Hollow check v3: no persistent enclosed columns, verdict false");
      return false;
    }

    int roofedColumns = 0;
    for (Map.Entry<Long, Integer> column : columnTops.entrySet()) {
      int x = (int) (column.getKey() >> 32);
      int z = column.getKey().intValue();
      boolean roofed = hasSolidNonLeafRoof(level, x, column.getValue(), z);
      BlastPlaster.debug("Hollow check v3: column {} {} top y {} roofed {}", x, z, column.getValue(), roofed);
      if (roofed) { roofedColumns++; }
      if (roofedColumns >= 3) {
        BlastPlaster.debug("Hollow check v3: verdict true, {} roofed columns", roofedColumns);
        return true;
      }
    }
    BlastPlaster.debug("Hollow check v3: verdict false, {} roofed columns", roofedColumns);
    return false;
  }

  private boolean hasSolidNonLeafRoof(Level level, int x, int topY, int z) {
    for (int dy = 1; dy <= 4; dy++) {
      BlockPos pos = new BlockPos(x, topY + dy, z);
      BlockState state = level.getBlockState(pos);
      if (state.getCollisionShape(level, pos).isEmpty()) { continue; }
      return !(state.getBlock() instanceof LeavesBlock) && !(BlastPlasterUtil.DT_LOADED && TreeHelper.isLeaves(state));
    }
    return false;
  }

  private Set<Long> enclosedCells(List<BlockPos> slice) {
    int minX = Integer.MAX_VALUE;
    int maxX = Integer.MIN_VALUE;
    int minZ = Integer.MAX_VALUE;
    int maxZ = Integer.MIN_VALUE;
    for (BlockPos p : slice) {
      int x = p.getX();
      int z = p.getZ();
      if (x < minX) { minX = x; }
      if (x > maxX) { maxX = x; }
      if (z < minZ) { minZ = z; }
      if (z > maxZ) { maxZ = z; }
    }
    int width = maxX - minX + 3;
    int depth = maxZ - minZ + 3;
    boolean[][] log = new boolean[width][depth];
    for (BlockPos p : slice) { log[p.getX() - minX + 1][p.getZ() - minZ + 1] = true; }
    boolean[][] reached = new boolean[width][depth];
    Deque<int[]> queue = new ArrayDeque<>();
    reached[0][0] = true;
    queue.add(new int[] { 0, 0 });
    while (!queue.isEmpty()) {
      int[] cell = queue.poll();
      for (int[] dir : new int[][] { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } }) {
        int nx = cell[0] + dir[0];
        int nz = cell[1] + dir[1];
        if (nx < 0 || nz < 0 || nx >= width || nz >= depth) { continue; }
        if (log[nx][nz] || reached[nx][nz]) { continue; }
        reached[nx][nz] = true;
        queue.add(new int[] { nx, nz });
      }
    }
    Set<Long> enclosed = new HashSet<>();
    for (int x = 0; x < width; x++) {
      for (int z = 0; z < depth; z++) {
        if (!log[x][z] && !reached[x][z]) { enclosed.add((((long) (x + minX - 1)) << 32) | ((z + minZ - 1) & 0xFFFFFFFFL)); }
      }
    }
    return enclosed;
  }

  private int countMaxHorizontalCluster(List<BlockPos> slice) {
    if (slice.isEmpty()) return 0;
    Set<BlockPos> visited = new HashSet<>();
    int maxCluster = 0;
    for (BlockPos start : slice) {
      if (visited.contains(start)) continue;
      Deque<BlockPos> q = new ArrayDeque<>();
      q.add(start); visited.add(start); int size = 1;
      while (!q.isEmpty()) {
        BlockPos cur = q.poll();
        for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
          BlockPos next = cur.relative(dir);
          if (slice.contains(next) && visited.add(next)) { q.add(next); size++; }
        }
      }
      if (size > maxCluster) maxCluster = size;
    }
    return maxCluster;
  }

  private void addConnectedVines(List<BlockStatePosWrapper> toHeal, Set<BlockPos> affectedPos, Level level) {
    Set<BlockPos> vineSeeds = new HashSet<>();
    for (BlockStatePosWrapper w : new ArrayList<>(toHeal)) {
      BlockPos p = w.getPos(); BlockState s = w.getState();
      if (s.is(BlockTags.LOGS) || s.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock) {
        for (BlockPos offset : BlastPlasterUtil.NEIGHBOR_POSITIONS) {
          BlockPos adj = p.offset(offset);
          if (!affectedPos.contains(adj)) {
            if (level.getBlockState(adj).getBlock() instanceof net.minecraft.world.level.block.VineBlock) vineSeeds.add(adj);
          }
        }
      }
    }
    for (BlockStatePosWrapper w : toHeal) if (w.getState().getBlock() instanceof net.minecraft.world.level.block.VineBlock) vineSeeds.add(w.getPos());
    if (vineSeeds.isEmpty()) return;

    Set<BlockPos> allVines = new HashSet<>();
    Deque<BlockPos> queue = new ArrayDeque<>(vineSeeds);
    Set<BlockPos> visited = new HashSet<>(vineSeeds);
    while (!queue.isEmpty()) {
      BlockPos p = queue.poll(); allVines.add(p);
      for (BlockPos offset : BlastPlasterUtil.NEIGHBOR_POSITIONS) {
        BlockPos adj = p.offset(offset);
        if (visited.add(adj) && level.getBlockState(adj).getBlock() instanceof net.minecraft.world.level.block.VineBlock) queue.add(adj);
      }
    }
    for (BlockPos p : allVines) if (!affectedPos.contains(p)) {
      toHeal.add(new BlockStatePosWrapper(level, p, level.getBlockState(p)));
      affectedPos.add(p);
    }
  }

  private int getLeafDistance(BlockState state) {
    if (state.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock) return state.getValue(net.minecraft.world.level.block.LeavesBlock.DISTANCE);
    return 7;
  }

  @SubscribeEvent public void onItemEntityJoin(EntityJoinLevelEvent event) {
    if (event.getLevel().isClientSide()) { return; }
    if (!(event.getEntity() instanceof ItemEntity item)) { return; }
    if (BlastPlasterUtil.shouldSuppressItemDrop(event.getLevel(), item)) {
      event.setCanceled(true);
    }
  }

  @SubscribeEvent public void onLivingDrops(LivingDropsEvent event) {
    Level level = event.getEntity().level();
    DamageSource source = event.getSource();
    if (!source.is(DamageTypeTags.IS_EXPLOSION)) { return; }
    if (Config.view(level).preventMobDrops()) { event.setCanceled(true); return; }
    for (ItemEntity item : event.getDrops()) { BlastPlasterUtil.markSuppressionBypass(item); }
  }

  @SubscribeEvent public void onPrimedTntJoin(EntityJoinLevelEvent event) {
    Level level = event.getLevel();
    if (level.isClientSide()) { return; }
    if (!(event.getEntity() instanceof PrimedTnt primed) || primed.getOwner() != null) { return; }
    markIfPunched(level, primed);
  }

  @SubscribeEvent(priority = EventPriority.HIGHEST)
  public void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
    Level level = event.getLevel();
    if (level.isClientSide()) { return; }
    if (!(level.getBlockState(event.getPos()).getBlock() instanceof TntBlock)) { return; }

    long now = level.getGameTime();
    Map<Long, Long> punches = recentTntPunches.computeIfAbsent(level.dimension(), ignored -> new HashMap<>());
    punches.values().removeIf(at -> now - at > PUNCH_WINDOW);
    punches.put(event.getPos().asLong(), now);
  }

  private static void markIfPunched(Level level, PrimedTnt primed) {
    Map<Long, Long> punches = recentTntPunches.get(level.dimension());
    if (punches == null || punches.isEmpty()) { return; }
    Long at = punches.get(primed.blockPosition().asLong());
    if (at == null || level.getGameTime() - at > PUNCH_WINDOW) { return; }
    primed.getPersistentData().putBoolean(PLAYER_LIT_TAG, true);
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
      level.getServer().schedule(new TickTask(baseTick + delay, () -> {
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

      level.getServer().schedule(new TickTask(baseTick + delay, () -> {
        level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, 1.0f, 1.0f, 1.0f), center.x, yBase, center.z, count, spread, spread, spread, 0.0);
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
    Map<Long, Long> burning = burningLights.computeIfAbsent(level.dimension(), ignored -> new HashMap<>());
    if (flashNear(burning, spot, now)) { return; }
    long key = spot.asLong();
    burning.put(key, now + duration);
    level.setBlock(spot, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, lightLevel), Block.UPDATE_CLIENTS);
    int currentTick = level.getServer().getTickCount();
    level.getServer().schedule(new TickTask(currentTick + duration, () -> {
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
