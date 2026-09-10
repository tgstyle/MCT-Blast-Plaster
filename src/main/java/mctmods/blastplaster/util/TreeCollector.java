package mctmods.blastplaster.util;

import mctmods.blastplaster.Config;

import com.dtteam.dynamictrees.api.network.MapSignal;
import com.dtteam.dynamictrees.api.network.NodeInspector;
import com.dtteam.dynamictrees.block.branch.SurfaceRootBlock;
import com.dtteam.dynamictrees.block.branch.TrunkShellBlock;
import com.dtteam.dynamictrees.block.fruit.FruitBlock;
import com.dtteam.dynamictrees.block.pod.PodBlock;
import com.dtteam.dynamictrees.tree.TreeHelper;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

@SuppressWarnings("unused")
public final class TreeCollector {

  private static final int TREE_DISTANCE_CAP = 60;
  private static final int TRUNK_CLUSTER_SPAN = 6;
  private static final int LEAF_BRUTE_RADIUS = 5;
  private static final int MAX_LEAF_DISTANCE_FOR_CHECK = 7;

  private TreeCollector() {}

  public static final class Tree {
    public final Set<BlockPos> logs = new HashSet<>();
    public final Set<BlockPos> leaves = new HashSet<>();

    public boolean isEmpty() { return logs.isEmpty(); }
  }

  public static Tree collect(LevelAccessor level, BlockPos seed, int maxBlocks) { return collect(level, seed, maxBlocks, unused -> true); }

  public static Tree collect(LevelAccessor level, BlockPos seed, int maxBlocks, Predicate<BlockPos> within) {
    if (!within.test(seed)) { return new Tree(); }

    if (BlastPlasterUtil.DT_LOADED && BlastPlasterUtil.isDynamicTreesAssembly(level.getBlockState(seed))) { return Dynamic.collect(level, seed.immutable(), maxBlocks, within); }

    TagKey<Block> logTag = Config.getLogTag(level.getBlockState(seed));
    if (logTag == null) { return new Tree(); }

    Set<BlockPos> seeds = new HashSet<>();
    seeds.add(seed.immutable());
    return gather(level, logTag, seeds, maxBlocks, within, true, false);
  }

  public static Tree expand(LevelAccessor level, TagKey<Block> logTag, Set<BlockPos> seeds, int maxBlocks) {
    if (seeds.isEmpty()) { return new Tree(); }
    return gather(level, logTag, seeds, maxBlocks, unused -> true, false, true);
  }

  private static Tree gather(LevelAccessor level, TagKey<Block> logTag, Set<BlockPos> seeds, int maxBlocks, Predicate<BlockPos> within, boolean vines, boolean seedTreesOnly) {
    Tree tree = new Tree();
    Block leafBlock = Config.getLeavesForLog(logTag);
    Set<BlockPos> visited = new HashSet<>();
    Deque<BlockPos> openSet = new ArrayDeque<>();
    Map<BlockPos, Integer> distance = new HashMap<>();
    Set<BlockPos> network = new HashSet<>();
    for (BlockPos seed : seeds) {
      BlockPos start = seed.immutable();
      visited.add(start);
      openSet.add(start);
      distance.put(start, 0);
      network.add(start);
    }
    while (!openSet.isEmpty()) {
      BlockPos pos = openSet.poll();
      int dist = distance.get(pos);
      for (BlockPos side : BlastPlasterUtil.NEIGHBOR_POSITIONS) {
        BlockPos adj = pos.offset(side);
        if (visited.contains(adj) || !within.test(adj)) { continue; }

        int newDist = dist + 1;
        if (newDist > TREE_DISTANCE_CAP || network.size() >= maxBlocks) { continue; }

        BlockState adjState = level.getBlockState(adj);
        if (adjState.is(logTag)) {
          visited.add(adj);
          openSet.add(adj);
          distance.put(adj, newDist);
          network.add(adj);
        }
        else if (leafBlock != null && adjState.is(leafBlock) && !persistent(adjState)) {
          visited.add(adj);
          distance.put(adj, newDist);
          tree.leaves.add(adj);
        }
      }
    }
    if (seedTreesOnly) { keepSeedTrees(level, seeds, network, tree); }
    else { tree.logs.addAll(network); }
    if (leafBlock == null) { return tree; }

    for (BlockPos logPos : tree.logs) {
      for (int dx = -LEAF_BRUTE_RADIUS; dx <= LEAF_BRUTE_RADIUS; dx++) {
        for (int dy = -LEAF_BRUTE_RADIUS; dy <= LEAF_BRUTE_RADIUS; dy++) {
          for (int dz = -LEAF_BRUTE_RADIUS; dz <= LEAF_BRUTE_RADIUS; dz++) {
            if (dx == 0 && dy == 0 && dz == 0) { continue; }

            BlockPos adj = logPos.offset(dx, dy, dz);
            if (visited.contains(adj) || !within.test(adj)) { continue; }

            BlockState adjState = level.getBlockState(adj);
            if (adjState.is(leafBlock) && !persistent(adjState)) {
              visited.add(adj);
              tree.leaves.add(adj);
            }
          }
        }
      }
    }

    int maxLeafSteps = MAX_LEAF_DISTANCE_FOR_CHECK - LEAF_BRUTE_RADIUS;
    Deque<BlockPos> leafQueue = new ArrayDeque<>(tree.leaves);
    Map<BlockPos, Integer> leafDepth = new HashMap<>();
    for (BlockPos held : tree.leaves) { leafDepth.put(held, 0); }
    while (!leafQueue.isEmpty()) {
      BlockPos leafPos = leafQueue.poll();
      int depth = leafDepth.get(leafPos);
      if (depth >= maxLeafSteps) { continue; }

      for (BlockPos side : BlastPlasterUtil.NEIGHBOR_POSITIONS) {
        BlockPos adj = leafPos.offset(side);
        if (visited.contains(adj) || !within.test(adj)) { continue; }

        BlockState adjState = level.getBlockState(adj);
        if (!adjState.is(leafBlock) || persistent(adjState)) { continue; }

        visited.add(adj);
        tree.leaves.add(adj);
        leafDepth.put(adj, depth + 1);
        leafQueue.add(adj);
      }
    }

    Set<BlockPos> kept = new HashSet<>();
    for (BlockPos leaf : tree.leaves) {
      int ours = minManhattanToSet(leaf, tree.logs);
      if (!hasCloserForeignLog(leaf, level, logTag, tree.logs, ours, within)) { kept.add(leaf); }
    }
    tree.leaves.clear();
    tree.leaves.addAll(kept);
    if (vines) { collectVines(level, tree, visited, within); }
    return tree;
  }

  private static void collectVines(LevelAccessor level, Tree tree, Set<BlockPos> visited, Predicate<BlockPos> within) {
    Deque<BlockPos> strands = new ArrayDeque<>();
    Set<BlockPos> anchors = new HashSet<>(tree.logs);
    anchors.addAll(tree.leaves);
    for (BlockPos held : anchors) {
      for (BlockPos side : BlastPlasterUtil.NEIGHBOR_POSITIONS) {
        BlockPos adj = held.offset(side);
        if (visited.contains(adj) || !within.test(adj)) { continue; }
        if (!level.getBlockState(adj).is(Blocks.VINE)) { continue; }

        visited.add(adj);
        tree.leaves.add(adj);
        strands.add(adj);
      }
    }
    while (!strands.isEmpty()) {
      BlockPos vine = strands.poll().below();
      while (!visited.contains(vine) && within.test(vine) && level.getBlockState(vine).is(Blocks.VINE)) {
        visited.add(vine);
        tree.leaves.add(vine);
        vine = vine.below();
      }
    }
  }

  private static void keepSeedTrees(LevelAccessor level, Set<BlockPos> seeds, Set<BlockPos> network, Tree tree) {
    List<Set<BlockPos>> clusters = rootClusters(level, network);
    if (clusters.size() <= 1) {
      tree.logs.addAll(network);
      return;
    }
    Map<BlockPos, Integer> owner = new HashMap<>();
    Deque<BlockPos> queue = new ArrayDeque<>();
    for (int i = 0; i < clusters.size(); i++) {
      for (BlockPos root : clusters.get(i)) {
        owner.put(root, i);
        queue.add(root);
      }
    }
    while (!queue.isEmpty()) {
      BlockPos pos = queue.poll();
      int who = owner.get(pos);
      for (BlockPos side : BlastPlasterUtil.NEIGHBOR_POSITIONS) {
        BlockPos adj = pos.offset(side);
        if (!network.contains(adj) || owner.containsKey(adj)) { continue; }

        owner.put(adj, who);
        queue.add(adj);
      }
    }
    Set<Integer> keep = new HashSet<>();
    for (BlockPos seed : seeds) {
      Integer who = owner.get(seed);
      if (who != null) { keep.add(who); }
    }
    if (keep.isEmpty()) {
      tree.logs.addAll(network);
      return;
    }
    for (BlockPos log : network) { if (keep.contains(owner.get(log))) { tree.logs.add(log); } }
  }

  private static List<Set<BlockPos>> rootClusters(LevelAccessor level, Set<BlockPos> network) {
    List<Set<BlockPos>> clusters = new ArrayList<>();
    for (BlockPos log : network) {
      BlockPos below = log.below();
      if (network.contains(below)) { continue; }

      BlockState ground = level.getBlockState(below);
      if (!ground.isFaceSturdy(level, below, Direction.UP) || ground.getBlock() instanceof LeavesBlock || Config.getLogTag(ground) != null) { continue; }

      Set<BlockPos> home = new HashSet<>();
      home.add(log);
      clusters.add(home);
    }
    boolean merged = true;
    while (merged) {
      merged = false;
      for (int i = 0; i < clusters.size() && !merged; i++) {
        for (int j = i + 1; j < clusters.size() && !merged; j++) {
          for (BlockPos a : clusters.get(i)) {
            for (BlockPos b : clusters.get(j)) {
              if (Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getZ() - b.getZ())) <= TRUNK_CLUSTER_SPAN) {
                clusters.get(i).addAll(clusters.remove(j));
                merged = true;
                break;
              }
            }
            if (merged) { break; }
          }
        }
      }
    }
    return clusters;
  }

  private static boolean persistent(BlockState state) {
    if (state.hasProperty(LeavesBlock.PERSISTENT)) { return state.getValue(LeavesBlock.PERSISTENT); }
    return false;
  }

  private static int minManhattanToSet(BlockPos target, Set<BlockPos> set) {
    int min = Integer.MAX_VALUE;
    for (BlockPos p : set) { min = Math.min(min, Math.abs(target.getX() - p.getX()) + Math.abs(target.getY() - p.getY()) + Math.abs(target.getZ() - p.getZ())); }
    return min;
  }

  private static boolean hasCloserForeignLog(BlockPos leaf, LevelAccessor level, TagKey<Block> logTag, Set<BlockPos> ourLogs, int ourDist, Predicate<BlockPos> within) {
    int budget = ourDist - 1;
    if (budget < 0) { return false; }

    BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
    for (int dx = -Math.min(8, budget); dx <= Math.min(8, budget); dx++) {
      int spanY = Math.min(8, budget - Math.abs(dx));
      for (int dy = -spanY; dy <= spanY; dy++) {
        int spanZ = Math.min(8, budget - Math.abs(dx) - Math.abs(dy));
        for (int dz = -spanZ; dz <= spanZ; dz++) {
          probe.set(leaf.getX() + dx, leaf.getY() + dy, leaf.getZ() + dz);
          if (ourLogs.contains(probe) || !within.test(probe)) { continue; }
          if (level.getBlockState(probe).is(logTag)) { return true; }
        }
      }
    }
    return false;
  }

  private static final class Dynamic {
    private Dynamic() {}

    static Tree collect(LevelAccessor level, BlockPos seed, int maxBlocks, Predicate<BlockPos> within) {
      Tree tree = new Tree();
      Level world = level instanceof Level held ? held : level instanceof ServerLevelAccessor server ? server.getLevel() : null;
      if (world == null) { return tree; }

      BlockPos root = rootOf(world, seed);
      if (root == null) { return tree; }

      Set<BlockPos> wood = new HashSet<>();
      TreeHelper.startAnalysisFromRoot(level, root, new MapSignal(new Collector(wood)));
      wood.remove(root);
      if (wood.isEmpty()) { return tree; }

      Set<BlockPos> shells = new HashSet<>();
      for (BlockPos branch : wood) {
        for (int dx = -1; dx <= 1; dx++) {
          for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) { continue; }

            BlockPos adj = branch.offset(dx, 0, dz);
            if (!wood.contains(adj) && level.getBlockState(adj).getBlock() instanceof TrunkShellBlock) { shells.add(adj); }
          }
        }
      }
      wood.addAll(shells);
      Set<BlockPos> seen = new HashSet<>(wood);
      seen.add(root);
      Deque<BlockPos> crawl = new ArrayDeque<>(seen);
      while (!crawl.isEmpty() && wood.size() < maxBlocks) {
        BlockPos at = crawl.poll();
        for (BlockPos side : BlastPlasterUtil.NEIGHBOR_POSITIONS) {
          BlockPos adj = at.offset(side);
          if (!seen.add(adj)) { continue; }
          if (level.getBlockState(adj).getBlock() instanceof SurfaceRootBlock) {
            wood.add(adj);
            crawl.add(adj);
          }
        }
      }
      for (BlockPos part : wood) { if (within.test(part)) { tree.logs.add(part); } }
      Set<BlockPos> visited = new HashSet<>(seen);
      for (BlockPos branch : wood) {
        for (int dx = -LEAF_BRUTE_RADIUS; dx <= LEAF_BRUTE_RADIUS; dx++) {
          for (int dy = -LEAF_BRUTE_RADIUS; dy <= LEAF_BRUTE_RADIUS; dy++) {
            for (int dz = -LEAF_BRUTE_RADIUS; dz <= LEAF_BRUTE_RADIUS; dz++) {
              if (dx == 0 && dy == 0 && dz == 0) { continue; }

              BlockPos adj = branch.offset(dx, dy, dz);
              if (visited.contains(adj) || !within.test(adj)) { continue; }

              BlockState held = level.getBlockState(adj);
              if (TreeHelper.isLeaves(held) || held.getBlock() instanceof FruitBlock || held.getBlock() instanceof PodBlock) {
                visited.add(adj);
                tree.leaves.add(adj);
              }
            }
          }
        }
      }
      collectVines(level, tree, visited, within);
      return tree;
    }

    @Nullable private static BlockPos rootOf(Level world, BlockPos seed) {
      BlockState held = world.getBlockState(seed);
      if (TreeHelper.isRooty(held)) { return seed.immutable(); }
      if (TreeHelper.isBranch(held)) { return rootFromBranch(world, seed); }

      for (BlockPos side : BlastPlasterUtil.NEIGHBOR_POSITIONS) {
        BlockPos adj = seed.offset(side);
        if (TreeHelper.isBranch(world.getBlockState(adj))) { return rootFromBranch(world, adj); }
      }
      return null;
    }

    @Nullable private static BlockPos rootFromBranch(Level world, BlockPos branch) {
      BlockPos root = TreeHelper.findRootNode(world, branch);
      return BlockPos.ZERO.equals(root) ? null : root.immutable();
    }

    private record Collector(Set<BlockPos> nodes) implements NodeInspector {
      @Override public boolean run(BlockState state, LevelAccessor level, BlockPos pos, Direction fromDir) {
        nodes.add(pos.immutable());
        return true;
      }

      @Override public boolean returnRun(BlockState state, LevelAccessor level, BlockPos pos, Direction fromDir) { return false; }
    }
  }
}
