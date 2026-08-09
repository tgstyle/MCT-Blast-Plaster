package mctmods.blastplaster.util;

import mctmods.blastplaster.Config;

import net.minecraft.block.BlockLeaves;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

@SuppressWarnings("unused")
public final class TreeCollector {
    private static final int TREE_DISTANCE_CAP = 60;
    private static final int LEAF_BRUTE_RADIUS = 5;
    private static final int MAX_LEAF_DISTANCE_FOR_CHECK = 7;

    private TreeCollector() {}

    public static final class Tree {
        public final Set<BlockPos> logs = new HashSet<>();
        public final Set<BlockPos> leaves = new HashSet<>();

        public boolean isEmpty() { return logs.isEmpty(); }
    }

    public static Tree collect(World world, BlockPos seed, int maxBlocks) { return collect(world, seed, maxBlocks, unused -> true); }

    public static Tree collect(World world, BlockPos seed, int maxBlocks, Predicate<BlockPos> within) {
        Tree tree = new Tree();
        if (!within.test(seed)) { return tree; }

        String logKey = Config.getLogKey(world.getBlockState(seed));
        if (logKey == null) { return tree; }

        String leafKey = Config.getLeavesKeyForLog(logKey);
        Set<BlockPos> visited = new HashSet<>();
        Deque<BlockPos> openSet = new ArrayDeque<>();
        Map<BlockPos, Integer> distance = new HashMap<>();
        BlockPos start = seed.toImmutable();
        visited.add(start);
        openSet.add(start);
        distance.put(start, 0);
        tree.logs.add(start);
        while (!openSet.isEmpty()) {
            BlockPos pos = openSet.poll();
            int dist = distance.get(pos);
            for (BlockPos side : BlastPlasterUtil.NEIGHBOR_POSITIONS) {
                BlockPos adj = pos.add(side);
                if (visited.contains(adj) || !within.test(adj)) { continue; }

                int newDist = dist + 1;
                if (newDist > TREE_DISTANCE_CAP || tree.logs.size() >= maxBlocks) { continue; }

                IBlockState adjState = world.getBlockState(adj);
                if (logKey.equals(Config.getLogKey(adjState))) {
                    visited.add(adj);
                    openSet.add(adj);
                    distance.put(adj, newDist);
                    tree.logs.add(adj);
                }
                else if (leafKey != null && leafKey.equals(Config.getLeavesKey(adjState)) && !persistent(adjState)) {
                    visited.add(adj);
                    distance.put(adj, newDist);
                    tree.leaves.add(adj);
                }
            }
        }
        if (leafKey == null) { return tree; }

        for (BlockPos logPos : tree.logs) {
            for (int dx = -LEAF_BRUTE_RADIUS; dx <= LEAF_BRUTE_RADIUS; dx++) {
                for (int dy = -LEAF_BRUTE_RADIUS; dy <= LEAF_BRUTE_RADIUS; dy++) {
                    for (int dz = -LEAF_BRUTE_RADIUS; dz <= LEAF_BRUTE_RADIUS; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) { continue; }

                        BlockPos adj = logPos.add(dx, dy, dz);
                        if (visited.contains(adj) || !within.test(adj)) { continue; }

                        IBlockState adjState = world.getBlockState(adj);
                        if (leafKey.equals(Config.getLeavesKey(adjState)) && !persistent(adjState)) {
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
                BlockPos adj = leafPos.add(side);
                if (visited.contains(adj) || !within.test(adj)) { continue; }

                IBlockState adjState = world.getBlockState(adj);
                if (!leafKey.equals(Config.getLeavesKey(adjState)) || persistent(adjState)) { continue; }

                visited.add(adj);
                tree.leaves.add(adj);
                leafDepth.put(adj, depth + 1);
                leafQueue.add(adj);
            }
        }

        Set<BlockPos> kept = new HashSet<>();
        for (BlockPos leaf : tree.leaves) {
            int ours = minManhattanToSet(leaf, tree.logs);
            if (!hasCloserForeignLog(leaf, world, logKey, tree.logs, ours, within)) { kept.add(leaf); }
        }
        tree.leaves.clear();
        tree.leaves.addAll(kept);
        return tree;
    }

    private static boolean persistent(IBlockState state) {
        if (state.getPropertyKeys().contains(BlockLeaves.DECAYABLE)) { return !state.getValue(BlockLeaves.DECAYABLE); }
        return false;
    }

    private static int minManhattanToSet(BlockPos target, Set<BlockPos> set) {
        int min = Integer.MAX_VALUE;
        for (BlockPos p : set) { min = Math.min(min, Math.abs(target.getX() - p.getX()) + Math.abs(target.getY() - p.getY()) + Math.abs(target.getZ() - p.getZ())); }
        return min;
    }

    private static boolean hasCloserForeignLog(BlockPos leaf, World world, String logKey, Set<BlockPos> ourLogs, int ourDist, Predicate<BlockPos> within) {
        int budget = ourDist - 1;
        if (budget < 0) { return false; }

        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (int dx = -Math.min(8, budget); dx <= Math.min(8, budget); dx++) {
            int spanY = Math.min(8, budget - Math.abs(dx));
            for (int dy = -spanY; dy <= spanY; dy++) {
                int spanZ = Math.min(8, budget - Math.abs(dx) - Math.abs(dy));
                for (int dz = -spanZ; dz <= spanZ; dz++) {
                    probe.setPos(leaf.getX() + dx, leaf.getY() + dy, leaf.getZ() + dz);
                    if (ourLogs.contains(probe) || !within.test(probe)) { continue; }
                    if (logKey.equals(Config.getLogKey(world.getBlockState(probe)))) { return true; }
                }
            }
        }
        return false;
    }
}
