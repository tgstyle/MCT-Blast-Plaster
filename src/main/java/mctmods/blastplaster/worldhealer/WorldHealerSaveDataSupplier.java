package mctmods.blastplaster.worldhealer;

import com.ferreusveritas.dynamictrees.api.TreeHelper;
import com.ferreusveritas.dynamictrees.api.network.MapSignal;
import com.ferreusveritas.dynamictrees.block.FruitBlock;
import com.ferreusveritas.dynamictrees.block.PodBlock;
import com.ferreusveritas.dynamictrees.block.branch.BasicRootsBlock;
import com.ferreusveritas.dynamictrees.block.branch.SurfaceRootBlock;
import com.ferreusveritas.dynamictrees.block.branch.TrunkShellBlock;
import com.ferreusveritas.dynamictrees.block.rooty.RootyBlock;

import mctmods.blastplaster.BlastPlaster;
import mctmods.blastplaster.Config;
import mctmods.blastplaster.helper.BlockStatePosWrapper;
import mctmods.blastplaster.helper.TickContainer;
import mctmods.blastplaster.helper.TickingHealList;
import mctmods.blastplaster.util.BlastPlasterUtil;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public class WorldHealerSaveDataSupplier extends SavedData implements java.util.function.Supplier<Object> {

  private Level level;
  private final TickingHealList healTask = new TickingHealList();
  private boolean dirtyFlag = false;
  static final String DATAKEY = BlastPlaster.MODID;

  private static final int TREE_DISTANCE_CAP = 60;
  private static final int MIN_EXPLODED_LOGS_FOR_EXPANSION = 2;
  private static final int MAX_EXTRA_BLOCKS = 8000;
  private static final int MAX_EXTRA_LOGS = 800;
  private static final float MIN_LEAF_LOG_RATIO = 0.4f;
  private static final float MIN_TREE_CONFIDENCE = 0.65f;
  private static final float MIN_LEAVES_PER_SEED = 0.8f;
  private static final int LEAF_BRUTE_RADIUS = 5;
  private static final int MAX_LEAF_DISTANCE_FOR_CHECK = 7;

  public WorldHealerSaveDataSupplier() {}

  public void onTick() {
    if (healTask.getQueue().isEmpty()) { return; }
    Collection<BlockStatePosWrapper> blocksToHeal = healTask.processTick();
    if (blocksToHeal != null) {
      if (Config.debugLogging() && !blocksToHeal.isEmpty()) {
        BlockStatePosWrapper first = blocksToHeal.iterator().next();
        BlastPlaster.LOGGER.info("Heal batch released: {} blocks at gameTime {} (first: {} at {})", blocksToHeal.size(), level.getGameTime(), first.getState().getBlock().getClass().getSimpleName(), first.getPos());
      }
      for (BlockStatePosWrapper blockData : blocksToHeal) { heal(blockData); }
      dirtyFlag = true;
    }
  }

  public void prepareAndScheduleHealing(List<BlockStatePosWrapper> toHeal, Set<BlockPos> affectedPos, Level level) { prepareAndScheduleHealing(toHeal, affectedPos, level, 0); }

  public void prepareAndScheduleHealing(List<BlockStatePosWrapper> toHeal, Set<BlockPos> affectedPos, Level level, int extraDelay) {
    if (toHeal.isEmpty()) { return; }

    addMultiBlockStructures(toHeal, affectedPos, level);

    int currentDelay = Config.getMinimumTicksBeforeHeal() + Math.max(0, extraDelay);
    List<BlockStatePosWrapper> dtPriority = BlastPlasterUtil.DT_LOADED ? extractDtPriorityBlocks(toHeal) : new ArrayList<>();

    List<BlockStatePosWrapper> dtRoots = new ArrayList<>();
    List<BlockStatePosWrapper> dtSurfaceRoots = new ArrayList<>();
    List<BlockStatePosWrapper> dtBranches = new ArrayList<>();
    List<BlockStatePosWrapper> dtShells = new ArrayList<>();
    List<BlockStatePosWrapper> dtLeaves = new ArrayList<>();
    List<BlockStatePosWrapper> dtFruitPods = new ArrayList<>();
    if (BlastPlasterUtil.DT_LOADED) {
      for (int i = toHeal.size() - 1; i >= 0; i--) {
        BlockStatePosWrapper w = toHeal.get(i);
        if (w.getState().getBlock() instanceof SurfaceRootBlock) {
          dtSurfaceRoots.add(w);
          toHeal.remove(i);
          continue;
        }
        if (w.getState().getBlock() instanceof BasicRootsBlock) {
          dtRoots.add(w);
          toHeal.remove(i);
        }
      }
      for (int i = toHeal.size() - 1; i >= 0; i--) {
        BlockStatePosWrapper w = toHeal.get(i);
        if (TreeHelper.isBranch(w.getState())) {
          dtBranches.add(w);
          toHeal.remove(i);
        }
      }
      for (int i = toHeal.size() - 1; i >= 0; i--) {
        BlockStatePosWrapper w = toHeal.get(i);
        if (w.getState().getBlock() instanceof TrunkShellBlock) {
          dtShells.add(w);
          toHeal.remove(i);
        }
      }
      for (int i = toHeal.size() - 1; i >= 0; i--) {
        BlockStatePosWrapper w = toHeal.get(i);
        if (TreeHelper.isLeaves(w.getState())) {
          dtLeaves.add(w);
          toHeal.remove(i);
        }
      }
      for (int i = toHeal.size() - 1; i >= 0; i--) {
        BlockStatePosWrapper w = toHeal.get(i);
        if (w.getState().getBlock() instanceof FruitBlock || w.getState().getBlock() instanceof PodBlock) {
          dtFruitPods.add(w);
          toHeal.remove(i);
        }
      }
    }

    List<BlockStatePosWrapper> nonVines = new ArrayList<>();
    List<BlockStatePosWrapper> vanillaLeaves = new ArrayList<>();
    List<BlockStatePosWrapper> vines = new ArrayList<>();
    List<BlockStatePosWrapper> bambooCane = new ArrayList<>();
    for (BlockStatePosWrapper w : toHeal) {
      if (w.getState().getBlock() instanceof VineBlock) { vines.add(w); }
      else {
        Block block = w.getState().getBlock();
        if (BlastPlasterUtil.DT_LOADED && (block == Blocks.BAMBOO || block == Blocks.SUGAR_CANE)) { bambooCane.add(w); }
        else if (block instanceof net.minecraft.world.level.block.LeavesBlock) { vanillaLeaves.add(w); }
        else { nonVines.add(w); }
      }
    }

    int groundEnd = scheduleLayeredHealing(nonVines, currentDelay);

    int pairBase = groundEnd + 4;
    for (BlockStatePosWrapper w : dtPriority) {
      int tick = (w.getState().getBlock() instanceof RootyBlock) ? pairBase : pairBase + 2;
      healTask.enqueue(tick, w);
    }

    int woodTick = pairBase + 6;
    int leavesTick = woodTick + 12;
    int fruitTick = leavesTick + 8;

    List<BlockStatePosWrapper> woodBatch = new ArrayList<>();
    woodBatch.addAll(dtRoots);
    woodBatch.addAll(dtBranches);
    woodBatch.addAll(dtShells);
    woodBatch.addAll(bambooCane);
    for (BlockStatePosWrapper item : woodBatch) { healTask.enqueue(woodTick, item); }

    if (!dtSurfaceRoots.isEmpty()) {
      dtSurfaceRoots.sort((a, b) -> Integer.compare(BlastPlasterUtil.getDTRadius(b.getState()), BlastPlasterUtil.getDTRadius(a.getState())));
      int surfaceTick = woodTick + 4;
      for (BlockStatePosWrapper item : dtSurfaceRoots) { healTask.enqueue(surfaceTick, item); }
    }

    List<BlockStatePosWrapper> leafBatch = new ArrayList<>();
    leafBatch.addAll(dtLeaves);
    leafBatch.addAll(vanillaLeaves);
    for (BlockStatePosWrapper item : leafBatch) { healTask.enqueue(leavesTick, item); }

    for (BlockStatePosWrapper item : dtFruitPods) { healTask.enqueue(fruitTick, item); }

    if (!woodBatch.isEmpty() || !leafBatch.isEmpty() || !dtPriority.isEmpty()) {
      BlastPlaster.debug("Heal timeline: ground ends {}, {} rooty pairs at {}, {} wood at {}, {} surface roots at {}, {} leaves at {}, {} fruit/pods at {}", groundEnd, dtPriority.size(), pairBase, woodBatch.size(), woodTick, dtSurfaceRoots.size(), woodTick + 4, leafBatch.size(), leavesTick, dtFruitPods.size(), fruitTick);
    }

    if (!vines.isEmpty()) {
      vines.sort((a, b) -> Integer.compare(b.getPos().getY(), a.getPos().getY()));
      int vineDelay = leavesTick + 80;
      int vineStep = Math.max(1, Math.min(12, 240 / vines.size()));
      for (BlockStatePosWrapper vine : vines) {
        healTask.enqueue(vineDelay, vine);
        vineDelay += vineStep;
      }
    }

    dirtyFlag = true;
  }

  private int scheduleLayeredHealing(List<BlockStatePosWrapper> blocks, int baseDelay) {
    if (blocks.isEmpty()) { return baseDelay; }
    TreeMap<Integer, List<BlockStatePosWrapper>> layers = new TreeMap<>();
    for (BlockStatePosWrapper wrapper : blocks) {
      int y = wrapper.getPos().getY();
      layers.computeIfAbsent(y, ignored -> new ArrayList<>()).add(wrapper);
    }
    int currentDelay = baseDelay;
    int var = Config.getRandomTickVar();
    for (List<BlockStatePosWrapper> layer : layers.values()) {
      int layerDelay = currentDelay;
      if (layer.size() == 1) {
        healTask.enqueue(layerDelay, layer.get(0));
        currentDelay += 20;
      } else {
        for (BlockStatePosWrapper wrapper : layer) {
          int delay = layerDelay + level.random.nextInt(var);
          healTask.enqueue(delay, wrapper);
        }
        currentDelay += var;
      }
    }
    return currentDelay;
  }

  private List<BlockStatePosWrapper> extractDtPriorityBlocks(List<BlockStatePosWrapper> toHeal) {
    if (!BlastPlasterUtil.DT_LOADED) { return new ArrayList<>(); }
    List<BlockStatePosWrapper> priority = new ArrayList<>();
    Set<BlockPos> toRemove = new HashSet<>();
    Set<BlockPos> seenRoots = new HashSet<>();

    Map<BlockPos, BlockStatePosWrapper> byPos = new HashMap<>();
    for (BlockStatePosWrapper w : toHeal) { byPos.put(w.getPos(), w); }

    for (BlockStatePosWrapper w : new ArrayList<>(toHeal)) {
      BlockState state = w.getState();
      if (state.getBlock() instanceof RootyBlock) {
        BlockPos rootPos = w.getPos();
        if (seenRoots.add(rootPos)) {
          priority.add(w);
          toRemove.add(rootPos);

          for (int dy = 1; dy <= 3; dy++) {
            BlockStatePosWrapper trunk = byPos.get(rootPos.above(dy));
            if (trunk == null) { continue; }
            if (!TreeHelper.isBranch(trunk.getState())) { continue; }
            if (toRemove.add(trunk.getPos())) { priority.add(trunk); }
            break;
          }
        }
      }
    }

    toHeal.removeIf(w -> toRemove.contains(w.getPos()));
    return priority;
  }


  private void addMultiBlockStructures(List<BlockStatePosWrapper> toHeal, Set<BlockPos> affectedPos, Level level) {
    List<BlockStatePosWrapper> extras = new ArrayList<>();
    for (BlockStatePosWrapper w : new ArrayList<>(toHeal)) {
      BlockPos pos = w.getPos();
      BlockState state = level.getBlockState(pos);
      Block block = state.getBlock();

      if (state.is(BlockTags.DOORS) && state.hasProperty(DoorBlock.HALF)) {
        DoubleBlockHalf half = state.getValue(DoorBlock.HALF);
        BlockPos otherPos = (half == DoubleBlockHalf.LOWER) ? pos.above() : pos.below();
        if (!affectedPos.contains(otherPos)) {
          BlockState otherState = level.getBlockState(otherPos);
          if (otherState.is(BlockTags.DOORS)) { extras.add(new BlockStatePosWrapper(level, otherPos, otherState)); }
        }
      }

      if (state.is(BlockTags.BEDS) && state.hasProperty(BedBlock.PART)) {
        BedPart part = state.getValue(BedBlock.PART);
        Direction facing = state.getValue(BedBlock.FACING);
        BlockPos otherPos = pos.relative(part == BedPart.HEAD ? facing.getOpposite() : facing);
        if (!affectedPos.contains(otherPos)) {
          BlockState otherState = level.getBlockState(otherPos);
          if (otherState.is(BlockTags.BEDS)) { extras.add(new BlockStatePosWrapper(level, otherPos, otherState)); }
        }
      }

      if ((state.is(BlockTags.TALL_FLOWERS) || block instanceof DoublePlantBlock) && state.hasProperty(DoublePlantBlock.HALF)) {
        DoubleBlockHalf half = state.getValue(DoublePlantBlock.HALF);
        BlockPos otherPos = (half == DoubleBlockHalf.LOWER) ? pos.above() : pos.below();
        if (!affectedPos.contains(otherPos)) {
          BlockState otherState = level.getBlockState(otherPos);
          if (otherState.is(BlockTags.TALL_FLOWERS)) { extras.add(new BlockStatePosWrapper(level, otherPos, otherState)); }
        }
      }

      if (block == Blocks.SUGAR_CANE || block == Blocks.BAMBOO) {
        BlastPlasterUtil.addVerticalColumn(extras, affectedPos, level, pos, block);
        for (BlockPos offset : BlastPlasterUtil.NEIGHBOR_POSITIONS) {
          BlockPos adj = pos.offset(offset);
          BlockState adjState = level.getBlockState(adj);
          if (adjState.getBlock() == block) { BlastPlasterUtil.addVerticalColumn(extras, affectedPos, level, adj, block); }
        }
      }

      if (block instanceof VineBlock) {
        for (BlockPos offset : BlastPlasterUtil.NEIGHBOR_POSITIONS) {
          BlockPos adj = pos.offset(offset);
          if (!affectedPos.contains(adj)) {
            BlockState adjState = level.getBlockState(adj);
            if (adjState.getBlock() instanceof VineBlock) { extras.add(new BlockStatePosWrapper(level, adj, adjState)); }
          }
        }
      }

      if (block == Blocks.JUNGLE_LOG || block == Blocks.JUNGLE_WOOD) {
        for (Direction dir : Direction.values()) {
          BlockPos adj = pos.relative(dir);
          if (!affectedPos.contains(adj)) {
            BlockState adjState = level.getBlockState(adj);
            if (adjState.getBlock() == Blocks.COCOA) { extras.add(new BlockStatePosWrapper(level, adj, adjState)); }
          }
        }
      }
    }
    toHeal.addAll(extras);
  }

  public void addExtraTreeBlocks(List<BlockStatePosWrapper> toHeal, Set<BlockPos> affectedPos, Level level) {
    boolean isDtLoaded = BlastPlasterUtil.DT_LOADED;
    boolean didDtExpansion = false;

    if (isDtLoaded) {
      Set<BlockPos> uniqueRoots = collectUniqueDynamicRoots(toHeal, affectedPos, level);
      if (!uniqueRoots.isEmpty()) {
        didDtExpansion = true;
        for (BlockPos rootPos : uniqueRoots) {
          Set<BlockPos> dtTreePos = new HashSet<>();
          CollectorNode branchCollector = new CollectorNode(dtTreePos);
          TreeHelper.startAnalysisFromRoot(level, rootPos, new MapSignal(branchCollector));

          Set<BlockPos> shellPos = new HashSet<>();
          for (BlockPos branch : dtTreePos) {
            for (int dx = -1; dx <= 1; dx++) {
              for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) { continue; }
                BlockPos adj = branch.offset(dx, 0, dz);
                if (!dtTreePos.contains(adj) && level.getBlockState(adj).getBlock() instanceof TrunkShellBlock) { shellPos.add(adj); }
              }
            }
          }
          dtTreePos.addAll(shellPos);

          Deque<BlockPos> rootQueue = new ArrayDeque<>();
          Set<BlockPos> rootVisited = new HashSet<>();
          rootQueue.add(rootPos);
          rootVisited.add(rootPos);
          for (BlockPos wood : dtTreePos) {
            if (rootVisited.add(wood)) { rootQueue.add(wood); }
          }
          int rootCap = Config.getMaxTreeSize();
          int rootsFound = 0;
          while (!rootQueue.isEmpty() && rootsFound < rootCap) {
            BlockPos rp = rootQueue.poll();
            for (BlockPos side : BlastPlasterUtil.NEIGHBOR_POSITIONS) {
              BlockPos adj = rp.offset(side);
              if (!rootVisited.add(adj)) { continue; }
              if (dtTreePos.contains(adj) || affectedPos.contains(adj)) { continue; }
              BlockState adjState = level.getBlockState(adj);
              if (adjState.getBlock() instanceof BasicRootsBlock || adjState.getBlock() instanceof SurfaceRootBlock) {
                dtTreePos.add(adj);
                rootQueue.add(adj);
                rootsFound++;
              }
            }
          }

          Set<BlockPos> extraLeaves = new HashSet<>();
          for (BlockPos branch : dtTreePos) {
            for (int dx = -LEAF_BRUTE_RADIUS; dx <= LEAF_BRUTE_RADIUS; dx++) {
              for (int dy = -LEAF_BRUTE_RADIUS; dy <= LEAF_BRUTE_RADIUS; dy++) {
                for (int dz = -LEAF_BRUTE_RADIUS; dz <= LEAF_BRUTE_RADIUS; dz++) {
                  if (dx == 0 && dy == 0 && dz == 0) { continue; }
                  BlockPos candidate = branch.offset(dx, dy, dz);
                  if (dtTreePos.contains(candidate) || affectedPos.contains(candidate)) { continue; }
                  BlockState candidateState = level.getBlockState(candidate);
                  if (TreeHelper.isLeaves(candidateState) || candidateState.getBlock() instanceof FruitBlock || candidateState.getBlock() instanceof PodBlock) { extraLeaves.add(candidate); }
                }
              }
            }
          }

          Set<BlockPos> filteredLeaves = new HashSet<>();
          for (BlockPos leaf : extraLeaves) {
            int dOur = minManhattanToSet(leaf, dtTreePos);
            int dForeign = Integer.MAX_VALUE;
            for (BlockPos otherRoot : uniqueRoots) {
              if (otherRoot.equals(rootPos)) { continue; }
              int d = Math.abs(leaf.getX() - otherRoot.getX()) + Math.abs(leaf.getY() - otherRoot.getY()) + Math.abs(leaf.getZ() - otherRoot.getZ());
              if (d < dForeign) { dForeign = d; }
            }
            if (dOur <= dForeign) { filteredLeaves.add(leaf); }
          }

          int totalBlocks = dtTreePos.size() + filteredLeaves.size();
          if (totalBlocks > Config.getMaxTreeSize()) {
            BlastPlaster.debug("Skipped huge DT tree expansion ({} blocks > max {})", totalBlocks, Config.getMaxTreeSize());
            continue;
          }

          for (BlockPos p : dtTreePos) {
            if (!affectedPos.contains(p)) {
              toHeal.add(new BlockStatePosWrapper(level, p, level.getBlockState(p)));
              affectedPos.add(p);
            }
          }

          for (BlockPos p : filteredLeaves) {
            if (!affectedPos.contains(p)) {
              toHeal.add(new BlockStatePosWrapper(level, p, level.getBlockState(p)));
              affectedPos.add(p);
            }
          }
        }
      }

      int severed = collectSeveredDynamicWood(toHeal, affectedPos, level, java.util.Collections.emptySet());
      if (severed > 0) {
        didDtExpansion = true;
        BlastPlaster.debug("Collected {} severed DT tree blocks via connectivity crawl", severed);
      }
    }

    if (!isDtLoaded || !didDtExpansion) {
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
        if (leafBlock == null) { continue; }

        Set<BlockPos> logSeeds = new HashSet<>();
        for (BlockStatePosWrapper w : toHeal) {
          if (w.getState().is(logTag)) { logSeeds.add(w.getPos()); }
        }

        if (logSeeds.isEmpty()) { continue; }

        List<Set<BlockPos>> trunkClusters = findConnectedLogClusters(logSeeds);

        for (Set<BlockPos> cluster : trunkClusters) {
          int originalSize = cluster.size();
          Set<BlockPos> seedSet = cluster;
          if (originalSize == 1) { seedSet = augmentVertical(cluster, level, logTag); }
          int clusterSize = seedSet.size();

          if (clusterSize < MIN_EXPLODED_LOGS_FOR_EXPANSION && originalSize != 1) { continue; }

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
              if (visited.contains(adj)) { continue; }
              BlockState adjState = level.getBlockState(adj);
              int newDist = dist + 1;
              if (newDist > TREE_DISTANCE_CAP) { continue; }

              if (adjState.is(logTag)) {
                visited.add(adj);
                openSet.add(adj);
                allLogs.add(adj);
                distanceMap.put(adj, newDist);
              } else if (adjState.getBlock() == leafBlock) {
                if (!isPersistentLeaf(adjState) && getLeafDistance(adjState) <= MAX_LEAF_DISTANCE_FOR_CHECK) {
                  visited.add(adj);
                  extraLeaves.add(adj);
                  distanceMap.put(adj, newDist);
                }
              }
            }
          }

          for (BlockPos logPos : allLogs) {
            for (int dx = -LEAF_BRUTE_RADIUS; dx <= LEAF_BRUTE_RADIUS; dx++) {
              for (int dy = -LEAF_BRUTE_RADIUS; dy <= LEAF_BRUTE_RADIUS; dy++) {
                for (int dz = -LEAF_BRUTE_RADIUS; dz <= LEAF_BRUTE_RADIUS; dz++) {
                  if (dx == 0 && dy == 0 && dz == 0) { continue; }
                  BlockPos adj = logPos.offset(dx, dy, dz);
                  if (visited.contains(adj)) { continue; }
                  BlockState adjState = level.getBlockState(adj);
                  if (adjState.getBlock() == leafBlock && !isPersistentLeaf(adjState) && getLeafDistance(adjState) <= MAX_LEAF_DISTANCE_FOR_CHECK) {
                    visited.add(adj);
                    extraLeaves.add(adj);
                  }
                }
              }
            }
          }

          Deque<BlockPos> leafQueue = new ArrayDeque<>(extraLeaves);
          while (!leafQueue.isEmpty()) {
            BlockPos leafPos = leafQueue.poll();
            int curDist = getLeafDistance(level.getBlockState(leafPos));
            for (BlockPos side : BlastPlasterUtil.NEIGHBOR_POSITIONS) {
              BlockPos adj = leafPos.offset(side);
              if (visited.contains(adj)) { continue; }
              BlockState adjState = level.getBlockState(adj);
              if (adjState.getBlock() != leafBlock) { continue; }
              if (isPersistentLeaf(adjState)) { continue; }
              int adjDist = getLeafDistance(adjState);
              if (adjDist < curDist || adjDist > MAX_LEAF_DISTANCE_FOR_CHECK) { continue; }
              visited.add(adj);
              extraLeaves.add(adj);
              leafQueue.add(adj);
            }
          }

          Set<BlockPos> filteredLeaves = new HashSet<>();
          for (BlockPos leaf : extraLeaves) {
            int dOur = minManhattanToSet(leaf, allLogs);
            int dForeign = minDistToForeignLogs(leaf, level, logTag, allLogs);
            if (dOur <= dForeign) { filteredLeaves.add(leaf); }
          }
          extraLeaves = filteredLeaves;

          int destroyedLeafCount = 0;
          for (BlockStatePosWrapper w : toHeal) {
            if (w.getState().getBlock() == leafBlock) { destroyedLeafCount++; }
          }

          int extraLeafCount = extraLeaves.size();
          int evidenceLeaves = extraLeafCount + destroyedLeafCount;
          int extraLogCount = allLogs.size() - clusterSize;
          int totalExtra = extraLogCount + extraLeafCount;
          float leafLogRatio = allLogs.isEmpty() ? 0f : (float)evidenceLeaves / allLogs.size();

          float confidence = calculateTreeConfidence(allLogs, extraLeaves, level);

          if (allLogs.size() + extraLeaves.size() > Config.getMaxTreeSize()) {
            BlastPlaster.debug("Skipped huge vanilla tree cluster ({} blocks > max {})", allLogs.size() + extraLeaves.size(), Config.getMaxTreeSize());
            continue;
          }

          if (evidenceLeaves < Math.max(4, (int)(clusterSize * MIN_LEAVES_PER_SEED))
                  || extraLogCount > MAX_EXTRA_LOGS
                  || totalExtra > MAX_EXTRA_BLOCKS
                  || leafLogRatio < MIN_LEAF_LOG_RATIO
                  || confidence < MIN_TREE_CONFIDENCE
                  || isHollowStructure(allLogs)) { continue; }

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
    }

    if (Config.healFullTrees()) { addConnectedVines(toHeal, affectedPos, level); }
  }

  public int collectSeveredDynamicWood(List<BlockStatePosWrapper> toHeal, Set<BlockPos> affectedPos, Level level, Set<BlockPos> persistentWoodSeeds) {
    if (!BlastPlasterUtil.DT_LOADED) { return 0; }
    Deque<BlockPos> queue = new ArrayDeque<>();
    Set<BlockPos> visited = new HashSet<>();

    for (BlockStatePosWrapper w : new ArrayList<>(toHeal)) {
      BlockState s = w.getState();
      if (TreeHelper.isBranch(s) || s.getBlock() instanceof TrunkShellBlock || s.getBlock() instanceof RootyBlock || s.getBlock() instanceof SurfaceRootBlock) {
        queue.add(w.getPos());
        visited.add(w.getPos());
      }
    }
    for (BlockPos seed : persistentWoodSeeds) {
      if (visited.add(seed)) { queue.add(seed); }
    }
    if (queue.isEmpty()) { return 0; }

    Set<BlockPos> severedWood = new HashSet<>();
    int cap = Config.getMaxTreeSize();

    while (!queue.isEmpty() && severedWood.size() < cap) {
      BlockPos pos = queue.poll();
      for (BlockPos side : BlastPlasterUtil.NEIGHBOR_POSITIONS) {
        BlockPos adj = pos.offset(side);
        if (!visited.add(adj)) { continue; }
        BlockState adjState = level.getBlockState(adj);
        if (affectedPos.contains(adj) || persistentWoodSeeds.contains(adj)) {
          if (persistentWoodSeeds.contains(adj) || TreeHelper.isBranch(adjState) || adjState.getBlock() instanceof TrunkShellBlock || adjState.getBlock() instanceof RootyBlock || adjState.getBlock() instanceof SurfaceRootBlock) { queue.add(adj); }
          continue;
        }
        if (TreeHelper.isBranch(adjState) || adjState.getBlock() instanceof TrunkShellBlock || adjState.getBlock() instanceof RootyBlock || adjState.getBlock() instanceof SurfaceRootBlock) {
          severedWood.add(adj);
          queue.add(adj);
        }
      }
    }

    if (severedWood.isEmpty()) { return 0; }

    Set<BlockPos> attachments = new HashSet<>();
    for (BlockPos wood : severedWood) {
      for (int dx = -LEAF_BRUTE_RADIUS; dx <= LEAF_BRUTE_RADIUS; dx++) {
        for (int dy = -LEAF_BRUTE_RADIUS; dy <= LEAF_BRUTE_RADIUS; dy++) {
          for (int dz = -LEAF_BRUTE_RADIUS; dz <= LEAF_BRUTE_RADIUS; dz++) {
            if (dx == 0 && dy == 0 && dz == 0) { continue; }
            BlockPos candidate = wood.offset(dx, dy, dz);
            if (severedWood.contains(candidate) || affectedPos.contains(candidate) || attachments.contains(candidate)) { continue; }
            BlockState cs = level.getBlockState(candidate);
            if (TreeHelper.isLeaves(cs) || cs.getBlock() instanceof FruitBlock || cs.getBlock() instanceof PodBlock) { attachments.add(candidate); }
          }
        }
      }
    }

    int added = 0;
    for (BlockPos p : severedWood) {
      if (!affectedPos.contains(p)) {
        toHeal.add(new BlockStatePosWrapper(level, p, level.getBlockState(p)));
        affectedPos.add(p);
        added++;
      }
    }
    for (BlockPos p : attachments) {
      if (!affectedPos.contains(p)) {
        toHeal.add(new BlockStatePosWrapper(level, p, level.getBlockState(p)));
        affectedPos.add(p);
        added++;
      }
    }
    return added;
  }

  private Set<BlockPos> collectUniqueDynamicRoots(List<BlockStatePosWrapper> toHeal, Set<BlockPos> affectedPos, Level level) {
    Set<BlockPos> uniqueRoots = new HashSet<>();
    for (BlockPos pos : affectedPos) {
      tryAddDynamicRoot(pos, level, uniqueRoots);
    }

    for (BlockStatePosWrapper w : toHeal) {
      if (!isDynamicTreePart(w.getState())) { continue; }
      BlockPos p = w.getPos();
      for (int dx = -LEAF_BRUTE_RADIUS; dx <= LEAF_BRUTE_RADIUS; dx++) {
        for (int dy = -LEAF_BRUTE_RADIUS; dy <= LEAF_BRUTE_RADIUS; dy++) {
          for (int dz = -LEAF_BRUTE_RADIUS; dz <= LEAF_BRUTE_RADIUS; dz++) {
            BlockPos adj = p.offset(dx, dy, dz);
            if (affectedPos.contains(adj)) { continue; }
            tryAddDynamicRoot(adj, level, uniqueRoots);
          }
        }
      }
    }

    if (uniqueRoots.isEmpty()) {
      for (BlockPos p : affectedPos) {
        for (int dx = -LEAF_BRUTE_RADIUS; dx <= LEAF_BRUTE_RADIUS; dx++) {
          for (int dy = -LEAF_BRUTE_RADIUS; dy <= LEAF_BRUTE_RADIUS; dy++) {
            for (int dz = -LEAF_BRUTE_RADIUS; dz <= LEAF_BRUTE_RADIUS; dz++) {
              BlockPos adj = p.offset(dx, dy, dz);
              tryAddDynamicRoot(adj, level, uniqueRoots);
            }
          }
        }
      }
    }
    return uniqueRoots;
  }

  private void tryAddDynamicRoot(BlockPos pos, Level level, Set<BlockPos> uniqueRoots) {
    if (!BlastPlasterUtil.DT_LOADED) { return; }
    BlockState s = level.getBlockState(pos);
    if (!isDynamicTreePart(s)) { return; }
    if (s.getBlock() instanceof FruitBlock || s.getBlock() instanceof PodBlock) { return; }
    if (s.getBlock() instanceof TrunkShellBlock || s.getBlock() instanceof SurfaceRootBlock) {
      for (int dx = -1; dx <= 1; dx++) {
        for (int dz = -1; dz <= 1; dz++) {
          if (dx == 0 && dz == 0) { continue; }
          BlockPos corePos = pos.offset(dx, 0, dz);
          if (TreeHelper.isBranch(level.getBlockState(corePos))) {
            BlockPos rootPos = TreeHelper.findRootNode(level, corePos);
            if (rootPos != BlockPos.ZERO) { uniqueRoots.add(rootPos.immutable()); }
            return;
          }
        }
      }
      return;
    }
    BlockPos rootPos = (TreeHelper.isBranch(s) || s.getBlock() instanceof BasicRootsBlock || s.getBlock() instanceof RootyBlock)
            ? TreeHelper.findRootNode(level, pos) : findRootFromLeaf(level, pos);
    if (rootPos != BlockPos.ZERO) { uniqueRoots.add(rootPos.immutable()); }
  }

  private boolean isDynamicTreePart(BlockState state) {
    if (!BlastPlasterUtil.DT_LOADED) { return false; }
    return TreeHelper.isBranch(state) || TreeHelper.isLeaves(state) || state.getBlock() instanceof BasicRootsBlock || state.getBlock() instanceof RootyBlock
            || state.getBlock() instanceof TrunkShellBlock || state.getBlock() instanceof SurfaceRootBlock || state.getBlock() instanceof FruitBlock || state.getBlock() instanceof PodBlock;
  }

  private List<Set<BlockPos>> findConnectedLogClusters(Set<BlockPos> seeds) {
    List<Set<BlockPos>> clusters = new ArrayList<>();
    Set<BlockPos> visited = new HashSet<>();
    for (BlockPos seed : new ArrayList<>(seeds)) {
      if (visited.contains(seed)) { continue; }
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
    if (cluster.size() != 1) { return cluster; }
    BlockPos start = cluster.iterator().next();
    Set<BlockPos> vertical = new HashSet<>();
    vertical.add(start);

    BlockPos p = start;
    while (true) {
      BlockPos next = p.above();
      if (!level.getBlockState(next).is(logTag)) { break; }
      vertical.add(next);
      p = next;
      if (vertical.size() > TREE_DISTANCE_CAP) { break; }
    }

    p = start;
    while (true) {
      BlockPos next = p.below();
      if (!level.getBlockState(next).is(logTag)) { break; }
      vertical.add(next);
      p = next;
      if (vertical.size() > TREE_DISTANCE_CAP) { break; }
    }

    return vertical;
  }

  private float calculateTreeConfidence(Set<BlockPos> allLogs, Set<BlockPos> extraLeaves, Level level) {
    if (allLogs.isEmpty()) { return 0f; }

    int minY = Integer.MAX_VALUE;
    int maxY = Integer.MIN_VALUE;
    for (BlockPos p : allLogs) {
      int y = p.getY();
      if (y < minY) { minY = y; }
      if (y > maxY) { maxY = y; }
    }
    int height = maxY - minY + 1;
    if (height < 4) { return 0.3f; }

    boolean hasGrounding = false;
    for (BlockPos log : allLogs) {
      if (log.getY() == minY) {
        BlockPos below = log.below();
        BlockState belowState = level.getBlockState(below);
        if (belowState.is(BlockTags.DIRT) || belowState.getBlock() == Blocks.GRASS_BLOCK || belowState.getBlock() == Blocks.PODZOL || belowState.getBlock() == Blocks.MYCELIUM) {
          hasGrounding = true;
          break;
        }
      }
    }

    int upperYThreshold = maxY - (int)(height * 0.4);
    int upperLeaves = 0;
    for (BlockPos leaf : extraLeaves) {
      if (leaf.getY() >= upperYThreshold) { upperLeaves++; }
    }
    float verticalBias = extraLeaves.isEmpty() ? 0f : (float)upperLeaves / extraLeaves.size();

    int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
    int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
    for (BlockPos p : allLogs) {
      int x = p.getX();
      int z = p.getZ();
      if (x < minX) { minX = x; }
      if (x > maxX) { maxX = x; }
      if (z < minZ) { minZ = z; }
      if (z > maxZ) { maxZ = z; }
    }
    int width = Math.max(maxX - minX + 1, maxZ - minZ + 1);
    float aspect = height / (float)Math.max(width, 1);

    boolean isPure = !hasArtificialStructuresNearby(allLogs, level);

    float confidence = 0.35f;
    if (hasGrounding) { confidence += 0.25f; }
    if (verticalBias >= 0.55f) { confidence += 0.2f; }
    if (aspect >= 1.55f) { confidence += 0.15f; }
    if (isPure) { confidence += 0.2f; }

    return Math.min(1.0f, confidence);
  }

  private boolean hasArtificialStructuresNearby(Set<BlockPos> allLogs, Level level) {
    for (BlockPos log : allLogs) {
      for (int dx = -4; dx <= 4; dx++) {
        for (int dy = -4; dy <= 4; dy++) {
          for (int dz = -4; dz <= 4; dz++) {
            BlockPos p = log.offset(dx, dy, dz);
            BlockState state = level.getBlockState(p);
            if (state.is(BlockTags.PLANKS) || state.is(BlockTags.WOODEN_FENCES) || state.is(BlockTags.WOODEN_STAIRS) || state.is(BlockTags.WOODEN_SLABS)) { return true; }
          }
        }
      }
    }
    return false;
  }

  private int minManhattanToSet(BlockPos target, Set<BlockPos> set) {
    int min = Integer.MAX_VALUE;
    for (BlockPos p : set) {
      min = Math.min(min, Math.abs(target.getX() - p.getX()) + Math.abs(target.getY() - p.getY()) + Math.abs(target.getZ() - p.getZ()));
    }
    return min;
  }

  private int minDistToForeignLogs(BlockPos leaf, Level level, TagKey<Block> logTag, Set<BlockPos> ourLogs) {
    int min = Integer.MAX_VALUE;
    for (int dx = -8; dx <= 8; dx++) {
      for (int dy = -8; dy <= 8; dy++) {
        for (int dz = -8; dz <= 8; dz++) {
          BlockPos p = leaf.offset(dx, dy, dz);
          if (ourLogs.contains(p)) { continue; }
          BlockState s = level.getBlockState(p);
          if (s.is(logTag)) {
            int d = Math.abs(leaf.getX() - p.getX()) + Math.abs(leaf.getY() - p.getY()) + Math.abs(leaf.getZ() - p.getZ());
            if (d < min) { min = d; }
          }
        }
      }
    }
    return min == Integer.MAX_VALUE ? 999 : min;
  }

  private boolean isHollowStructure(Set<BlockPos> allLogs) {
    Map<Integer, List<BlockPos>> logsByY = new HashMap<>();
    for (BlockPos p : allLogs) { logsByY.computeIfAbsent(p.getY(), ignored -> new ArrayList<>()).add(p); }

    for (List<BlockPos> slice : logsByY.values()) {
      if (slice.size() < 9) { continue; }
      int connectedHorizontal = countMaxHorizontalCluster(slice);
      if (connectedHorizontal >= 7) { return true; }
    }
    return false;
  }

  private int countMaxHorizontalCluster(List<BlockPos> slice) {
    if (slice.isEmpty()) { return 0; }
    Set<BlockPos> visited = new HashSet<>();
    int maxCluster = 0;

    for (BlockPos start : slice) {
      if (visited.contains(start)) { continue; }
      Deque<BlockPos> q = new ArrayDeque<>();
      q.add(start);
      visited.add(start);
      int size = 1;

      while (!q.isEmpty()) {
        BlockPos cur = q.poll();
        for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
          BlockPos next = cur.relative(dir);
          if (slice.contains(next) && visited.add(next)) {
            q.add(next);
            size++;
          }
        }
      }
      if (size > maxCluster) { maxCluster = size; }
    }
    return maxCluster;
  }

  private void addConnectedVines(List<BlockStatePosWrapper> toHeal, Set<BlockPos> affectedPos, Level level) {
    Set<BlockPos> vineSeeds = new HashSet<>();
    for (BlockStatePosWrapper w : new ArrayList<>(toHeal)) {
      BlockPos p = w.getPos();
      BlockState s = level.getBlockState(p);
      if (s.is(BlockTags.LOGS) || s.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock || (BlastPlasterUtil.DT_LOADED && TreeHelper.isLeaves(s))) {
        for (BlockPos offset : BlastPlasterUtil.NEIGHBOR_POSITIONS) {
          BlockPos adj = p.offset(offset);
          if (!affectedPos.contains(adj)) {
            BlockState adjState = level.getBlockState(adj);
            if (adjState.getBlock() instanceof VineBlock) { vineSeeds.add(adj); }
          }
        }
      }
    }
    for (BlockStatePosWrapper w : toHeal) {
      if (w.getState().getBlock() instanceof VineBlock) { vineSeeds.add(w.getPos()); }
    }
    if (vineSeeds.isEmpty()) { return; }

    Set<BlockPos> allVines = new HashSet<>();
    Deque<BlockPos> queue = new ArrayDeque<>(vineSeeds);
    Set<BlockPos> visited = new HashSet<>(vineSeeds);

    while (!queue.isEmpty()) {
      BlockPos p = queue.poll();
      allVines.add(p);
      for (BlockPos offset : BlastPlasterUtil.NEIGHBOR_POSITIONS) {
        BlockPos adj = p.offset(offset);
        if (visited.add(adj)) {
          BlockState adjState = level.getBlockState(adj);
          if (adjState.getBlock() instanceof VineBlock) { queue.add(adj); }
        }
      }
    }

    for (BlockPos p : allVines) {
      if (!affectedPos.contains(p)) {
        BlockState state = level.getBlockState(p);
        toHeal.add(new BlockStatePosWrapper(level, p, state));
        affectedPos.add(p);
      }
    }
  }

  private boolean isPersistentLeaf(BlockState state) {
    if (state.hasProperty(net.minecraft.world.level.block.LeavesBlock.PERSISTENT)) { return state.getValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT); }
    return false;
  }

  private int getLeafDistance(BlockState state) {
    if (BlastPlasterUtil.DT_LOADED && TreeHelper.isLeaves(state)) {
      IntegerProperty hydroProp = (IntegerProperty) state.getBlock().getStateDefinition().getProperty("hydro");
      if (hydroProp != null) { return 5 - state.getValue(hydroProp); }
      return 7;
    }
    if (state.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock) { return state.getValue(net.minecraft.world.level.block.LeavesBlock.DISTANCE); }
    return 0;
  }

  private BlockPos findRootFromLeaf(Level world, BlockPos leafPos) {
    if (!BlastPlasterUtil.DT_LOADED) { return BlockPos.ZERO; }
    BlockState state = world.getBlockState(leafPos);
    if (!TreeHelper.isLeaves(state)) { return BlockPos.ZERO; }
    int currentDist = getLeafDistance(state);
    BlockPos pos = leafPos;
    Set<BlockPos> visited = new HashSet<>();
    while (currentDist > 1) {
      visited.add(pos);
      int minNeighDist = currentDist;
      BlockPos nextPos = null;
      for (Direction dir : Direction.values()) {
        BlockPos adj = pos.relative(dir);
        if (visited.contains(adj)) { continue; }
        BlockState adjState = world.getBlockState(adj);
        int neighDist = getLeafDistance(adjState);
        if (neighDist > 0 && neighDist < minNeighDist) {
          minNeighDist = neighDist;
          nextPos = adj;
        }
      }
      if (nextPos == null) { return BlockPos.ZERO; }
      pos = nextPos;
      currentDist = minNeighDist;
    }
    for (Direction dir : Direction.values()) {
      BlockPos adj = pos.relative(dir);
      BlockState adjState = world.getBlockState(adj);
      if (TreeHelper.isBranch(adjState)) { return TreeHelper.findRootNode(world, adj); }
    }
    return BlockPos.ZERO;
  }

  private void heal(BlockStatePosWrapper blockData) {
    BlockPos pos = blockData.getPos();
    BlockState restoreState = blockData.getState();

    Block block = restoreState.getBlock();
    if (block == Blocks.BAMBOO || block == Blocks.SUGAR_CANE) {
      level.setBlock(pos, restoreState, 3);
      if (blockData.getEntityTag() != null) {
        BlockEntity te = level.getBlockEntity(pos);
        if (te != null) { te.load(blockData.getEntityTag()); }
      }
      return;
    }

    if (BlastPlasterUtil.DT_LOADED && isDynamicTreePart(restoreState)) {
      level.setBlock(pos, restoreState, 3);
      if (blockData.getEntityTag() != null) {
        BlockEntity te = level.getBlockEntity(pos);
        if (te != null) { te.load(blockData.getEntityTag()); }
      }
      return;
    }

    BlockState currentState = level.getBlockState(pos);
    if (currentState.equals(restoreState)) { return; }

    if (restoreState.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock
            && restoreState.hasProperty(net.minecraft.world.level.block.LeavesBlock.DISTANCE)
            && restoreState.hasProperty(net.minecraft.world.level.block.LeavesBlock.PERSISTENT)
            && !restoreState.getValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT)
            && restoreState.getValue(net.minecraft.world.level.block.LeavesBlock.DISTANCE) >= 7) {
      restoreState = restoreState.setValue(net.minecraft.world.level.block.LeavesBlock.DISTANCE, 6);
    }

    FluidState fluid = level.getFluidState(pos);
    boolean isEmpty = currentState.isAir();
    boolean hasFluid = !fluid.isEmpty();

    if (Config.isOverride() || isEmpty || hasFluid) {
      level.setBlock(pos, restoreState, 3);
      if (blockData.getEntityTag() != null) {
        BlockEntity te = level.getBlockEntity(pos);
        if (te != null) { te.load(blockData.getEntityTag()); }
      }
    }
  }

  public void markDirty() { dirtyFlag = true; }

  public int getMaxQueuedDelayTicks() {
    int sum = 0;
    for (TickContainer<Collection<BlockStatePosWrapper>> tc : healTask.getQueue()) { sum += tc.getTicks(); }
    return sum;
  }

  @Override @NotNull public CompoundTag save(@NotNull CompoundTag tag) {
    ListTag tagList = new ListTag();
    for (TickContainer<Collection<BlockStatePosWrapper>> tc : healTask.getQueue()) {
      CompoundTag tcTag = new CompoundTag();
      tcTag.putInt("ticks", tc.getTicks());
      ListTag bdList = new ListTag();
      for (BlockStatePosWrapper bd : tc.getValue()) {
        CompoundTag bdTag = new CompoundTag();
        bd.writeNBT(bdTag);
        bdList.add(bdTag);
      }
      tcTag.put("blockDataList", bdList);
      tagList.add(tcTag);
    }
    tag.put("healTaskList", tagList);
    RegionSnapshotHealer.saveJobs(level, tag);
    return tag;
  }

  public void deserializeNBT(CompoundTag tag) {
    ListTag tagList = tag.getList("healTaskList", Tag.TAG_COMPOUND);
    int cumulative = 0;
    int restored = 0;
    for (Tag t : tagList) {
      CompoundTag tcTag = (CompoundTag) t;
      cumulative += tcTag.getInt("ticks");
      ListTag bdListTag = tcTag.getList("blockDataList", Tag.TAG_COMPOUND);
      for (Tag bt : bdListTag) {
        CompoundTag bdTag = (CompoundTag) bt;
        BlockStatePosWrapper bd = new BlockStatePosWrapper();
        bd.readNBT(bdTag, level);
        healTask.enqueue(Math.max(1, cumulative), bd);
        restored++;
      }
    }
    if (restored > 0) {
      dirtyFlag = true;
      BlastPlaster.LOGGER.info("[BlastPlaster] Restored heal queue: {} blocks resuming over {} ticks for {}", restored, cumulative, level.dimension().location());
    }
    RegionSnapshotHealer.loadJobs(level, tag);
  }

  public static WorldHealerSaveDataSupplier loadWorldHealer(ServerLevel serverLevelIn) {
    DimensionDataStorage storage = serverLevelIn.getDataStorage();
    return storage.computeIfAbsent(tag -> {
      WorldHealerSaveDataSupplier w = new WorldHealerSaveDataSupplier();
      w.level = serverLevelIn;
      w.deserializeNBT(tag);
      return w;
    }, () -> {
      WorldHealerSaveDataSupplier w = new WorldHealerSaveDataSupplier();
      w.level = serverLevelIn;
      return w;
    }, DATAKEY);
  }

  @Override public boolean isDirty() {
    boolean d = dirtyFlag;
    dirtyFlag = false;
    return d || !healTask.getQueue().isEmpty();
  }

  @Override public Object get() { return this; }

  private record CollectorNode(Set<BlockPos> nodeSet) implements com.ferreusveritas.dynamictrees.api.network.NodeInspector {
    @Override public boolean run(BlockState state, LevelAccessor level, BlockPos pos, Direction fromDir) {
      nodeSet.add(pos.immutable());
      return true;
    }

    @Override public boolean returnRun(BlockState state, LevelAccessor level, BlockPos pos, Direction fromDir) { return false; }
  }
}
