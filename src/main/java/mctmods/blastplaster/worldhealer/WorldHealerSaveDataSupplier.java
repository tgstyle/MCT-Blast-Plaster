package mctmods.blastplaster.worldhealer;

import mctmods.blastplaster.BlastPlaster;
import mctmods.blastplaster.Config;
import mctmods.blastplaster.helper.BlockStatePosWrapper;
import mctmods.blastplaster.helper.TickContainer;
import mctmods.blastplaster.helper.TickingHealList;
import mctmods.blastplaster.util.BlastPlasterUtil;

import net.minecraft.block.Block;
import net.minecraft.block.BlockBed;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockDoublePlant;
import net.minecraft.block.BlockFence;
import net.minecraft.block.BlockHugeMushroom;
import net.minecraft.block.BlockLeaves;
import net.minecraft.block.BlockOldLog;
import net.minecraft.block.BlockPlanks;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.BlockStairs;
import net.minecraft.block.BlockVine;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraftforge.common.util.Constants;
import com.ferreusveritas.dynamictrees.api.TreeHelper;
import com.ferreusveritas.dynamictrees.api.network.INodeInspector;
import com.ferreusveritas.dynamictrees.api.network.MapSignal;
import com.ferreusveritas.dynamictrees.blocks.BlockDynamicLeaves;
import com.ferreusveritas.dynamictrees.blocks.BlockFruit;
import com.ferreusveritas.dynamictrees.blocks.BlockFruitCocoa;
import com.ferreusveritas.dynamictrees.blocks.BlockRooty;
import com.ferreusveritas.dynamictrees.blocks.BlockSurfaceRoot;
import com.ferreusveritas.dynamictrees.blocks.BlockTrunkShell;

import javax.annotation.Nonnull;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public class WorldHealerSaveDataSupplier extends WorldSavedData {

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
    private final TickingHealList healTask = new TickingHealList();
    private World world;

    public WorldHealerSaveDataSupplier(String name) { super(name); }

    public void onTick() {
        if (healTask.getQueue().isEmpty()) { return; }
        Collection<BlockStatePosWrapper> blocksToHeal = healTask.processTick();
        if (blocksToHeal != null) {
            if (Config.debugLogging() && !blocksToHeal.isEmpty()) {
                BlockStatePosWrapper first = blocksToHeal.iterator().next();
                BlastPlaster.logger.info("Heal batch released: {} blocks at gameTime {} (first: {} at {})", blocksToHeal.size(), world.getTotalWorldTime(), first.getState().getBlock().getClass().getSimpleName(), first.getPos());
            }
            for (BlockStatePosWrapper blockData : blocksToHeal) { heal(blockData); }
            markDirty();
        }
    }

    public List<BlockStatePosWrapper> prepareAndScheduleHealing(List<BlockStatePosWrapper> toHeal, Set<BlockPos> affectedPos, World world) { return prepareAndScheduleHealing(toHeal, affectedPos, world, 0); }

    public List<BlockStatePosWrapper> prepareAndScheduleHealing(List<BlockStatePosWrapper> toHeal, Set<BlockPos> affectedPos, World world, int extraDelay) {
        if (toHeal.isEmpty()) { return toHeal; }

        addMultiBlockStructures(toHeal, affectedPos, world);
        List<BlockStatePosWrapper> scheduled = new ArrayList<>(toHeal);

        int currentDelay = Config.view(world).getMinimumTicksBeforeHeal() + Math.max(0, extraDelay);
        List<BlockStatePosWrapper> dtPriority = BlastPlasterUtil.DT_LOADED ? extractDtPriorityBlocks(toHeal) : new ArrayList<>();

        List<BlockStatePosWrapper> dtSurfaceRoots = new ArrayList<>();
        List<BlockStatePosWrapper> dtBranches = new ArrayList<>();
        List<BlockStatePosWrapper> dtShells = new ArrayList<>();
        List<BlockStatePosWrapper> dtLeaves = new ArrayList<>();
        List<BlockStatePosWrapper> dtFruitPods = new ArrayList<>();
        if (BlastPlasterUtil.DT_LOADED) {
            for (int i = toHeal.size() - 1; i >= 0; i--) {
                BlockStatePosWrapper w = toHeal.get(i);
                if (w.getState().getBlock() instanceof BlockSurfaceRoot) {
                    dtSurfaceRoots.add(w);
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
                if (w.getState().getBlock() instanceof BlockTrunkShell) {
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
                if (w.getState().getBlock() instanceof BlockFruit || w.getState().getBlock() instanceof BlockFruitCocoa) {
                    dtFruitPods.add(w);
                    toHeal.remove(i);
                }
            }
        }

        List<BlockStatePosWrapper> nonVines = new ArrayList<>();
        List<BlockStatePosWrapper> vanillaLeaves = new ArrayList<>();
        List<BlockStatePosWrapper> vines = new ArrayList<>();
        List<BlockStatePosWrapper> canes = new ArrayList<>();
        for (BlockStatePosWrapper w : toHeal) {
            Block block = w.getState().getBlock();
            if (block instanceof BlockVine) { vines.add(w); }
            else if (BlastPlasterUtil.DT_LOADED && block == Blocks.REEDS) { canes.add(w); }
            else if (block instanceof BlockLeaves) { vanillaLeaves.add(w); }
            else { nonVines.add(w); }
        }

        int groundEnd = scheduleLayeredHealing(nonVines, currentDelay);

        int pairBase = groundEnd + 4;
        for (BlockStatePosWrapper w : dtPriority) {
            int tick = (w.getState().getBlock() instanceof BlockRooty) ? pairBase : pairBase + 2;
            healTask.enqueue(tick, w);
        }

        int woodTick = pairBase + 6;
        int leavesTick = woodTick + 12;
        int fruitTick = leavesTick + 8;

        List<BlockStatePosWrapper> woodBatch = new ArrayList<>();
        woodBatch.addAll(dtBranches);
        woodBatch.addAll(dtShells);
        woodBatch.addAll(canes);
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

        markDirty();
        return scheduled;
    }

    private int scheduleLayeredHealing(List<BlockStatePosWrapper> blocks, int baseDelay) {
        if (blocks.isEmpty()) { return baseDelay; }
        TreeMap<Integer, List<BlockStatePosWrapper>> layers = new TreeMap<>();
        for (BlockStatePosWrapper wrapper : blocks) {
            int y = wrapper.getPos().getY();
            List<BlockStatePosWrapper> layer = layers.computeIfAbsent(y, k -> new ArrayList<>());
            layer.add(wrapper);
        }
        int currentDelay = baseDelay;
        int var = Config.view(world).getRandomTickVar();
        for (List<BlockStatePosWrapper> layer : layers.values()) {
            int layerDelay = currentDelay;
            if (layer.size() == 1) {
                healTask.enqueue(layerDelay, layer.get(0));
                currentDelay += 20;
            }
            else {
                for (BlockStatePosWrapper wrapper : layer) { healTask.enqueue(layerDelay + world.rand.nextInt(var), wrapper); }
                currentDelay += var;
            }
        }
        return currentDelay;
    }

    private List<BlockStatePosWrapper> extractDtPriorityBlocks(List<BlockStatePosWrapper> toHeal) {
        List<BlockStatePosWrapper> priority = new ArrayList<>();
        if (!BlastPlasterUtil.DT_LOADED) { return priority; }
        Set<BlockPos> toRemove = new HashSet<>();
        Set<BlockPos> seenRoots = new HashSet<>();

        Map<BlockPos, BlockStatePosWrapper> byPos = new HashMap<>();
        for (BlockStatePosWrapper w : toHeal) { byPos.put(w.getPos(), w); }

        for (BlockStatePosWrapper w : new ArrayList<>(toHeal)) {
            IBlockState state = w.getState();
            if (state.getBlock() instanceof BlockRooty) {
                BlockPos rootPos = w.getPos();
                if (seenRoots.add(rootPos)) {
                    priority.add(w);
                    toRemove.add(rootPos);

                    for (int dy = 1; dy <= 3; dy++) {
                        BlockStatePosWrapper trunk = byPos.get(rootPos.up(dy));
                        if (trunk == null) { continue; }
                        if (!TreeHelper.isBranch(trunk.getState())) { continue; }
                        if (toRemove.add(trunk.getPos())) { priority.add(trunk); }
                        break;
                    }
                }
            }
        }

        for (int i = toHeal.size() - 1; i >= 0; i--) {
            if (toRemove.contains(toHeal.get(i).getPos())) { toHeal.remove(i); }
        }
        return priority;
    }

    private void addMultiBlockStructures(List<BlockStatePosWrapper> toHeal, Set<BlockPos> affectedPos, World world) {
        List<BlockStatePosWrapper> extras = new ArrayList<>();
        for (BlockStatePosWrapper w : new ArrayList<>(toHeal)) {
            BlockPos pos = w.getPos();
            IBlockState state = world.getBlockState(pos);
            Block block = state.getBlock();

            if (block instanceof BlockDoor) {
                BlockPos otherPos = state.getValue(BlockDoor.HALF) == BlockDoor.EnumDoorHalf.LOWER ? pos.up() : pos.down();
                if (!affectedPos.contains(otherPos)) {
                    IBlockState otherState = world.getBlockState(otherPos);
                    if (otherState.getBlock() instanceof BlockDoor) { extras.add(new BlockStatePosWrapper(world, otherPos, otherState)); }
                }
            }

            if (block instanceof BlockBed) {
                EnumFacing facing = state.getValue(BlockBed.FACING);
                BlockPos otherPos = pos.offset(state.getValue(BlockBed.PART) == BlockBed.EnumPartType.HEAD ? facing.getOpposite() : facing);
                if (!affectedPos.contains(otherPos)) {
                    IBlockState otherState = world.getBlockState(otherPos);
                    if (otherState.getBlock() instanceof BlockBed) { extras.add(new BlockStatePosWrapper(world, otherPos, otherState)); }
                }
            }

            if (block instanceof BlockDoublePlant) {
                BlockPos otherPos = state.getValue(BlockDoublePlant.HALF) == BlockDoublePlant.EnumBlockHalf.LOWER ? pos.up() : pos.down();
                if (!affectedPos.contains(otherPos)) {
                    IBlockState otherState = world.getBlockState(otherPos);
                    if (otherState.getBlock() instanceof BlockDoublePlant) { extras.add(new BlockStatePosWrapper(world, otherPos, otherState)); }
                }
            }

            if (block == Blocks.REEDS) {
                BlastPlasterUtil.addVerticalColumn(extras, affectedPos, world, pos, block);
                for (BlockPos offset : BlastPlasterUtil.NEIGHBOR_POSITIONS) {
                    BlockPos adj = pos.add(offset);
                    if (world.getBlockState(adj).getBlock() == block) { BlastPlasterUtil.addVerticalColumn(extras, affectedPos, world, adj, block); }
                }
            }

            if (block instanceof BlockVine) {
                for (BlockPos offset : BlastPlasterUtil.NEIGHBOR_POSITIONS) {
                    BlockPos adj = pos.add(offset);
                    if (!affectedPos.contains(adj)) {
                        IBlockState adjState = world.getBlockState(adj);
                        if (adjState.getBlock() instanceof BlockVine) { extras.add(new BlockStatePosWrapper(world, adj, adjState)); }
                    }
                }
            }

            if (isJungleLog(state)) {
                for (EnumFacing dir : EnumFacing.values()) {
                    BlockPos adj = pos.offset(dir);
                    if (!affectedPos.contains(adj)) {
                        IBlockState adjState = world.getBlockState(adj);
                        if (adjState.getBlock() == Blocks.COCOA) { extras.add(new BlockStatePosWrapper(world, adj, adjState)); }
                    }
                }
            }
        }
        toHeal.addAll(extras);
    }

    public void addExtraTreeBlocks(List<BlockStatePosWrapper> toHeal, Set<BlockPos> affectedPos, World world) {
        boolean isDtLoaded = BlastPlasterUtil.DT_LOADED;
        boolean didDtExpansion = false;

        if (isDtLoaded) {
            Set<BlockPos> uniqueRoots = collectUniqueDynamicRoots(toHeal, affectedPos, world);
            if (!uniqueRoots.isEmpty()) {
                didDtExpansion = true;
                for (BlockPos rootPos : uniqueRoots) {
                    Set<BlockPos> dtTreePos = new HashSet<>();
                    TreeHelper.startAnalysisFromRoot(world, rootPos, new MapSignal(new CollectorNode(dtTreePos)));

                    Set<BlockPos> shellPos = new HashSet<>();
                    for (BlockPos branch : dtTreePos) {
                        for (int dx = -1; dx <= 1; dx++) {
                            for (int dz = -1; dz <= 1; dz++) {
                                if (dx == 0 && dz == 0) { continue; }
                                BlockPos adj = branch.add(dx, 0, dz);
                                if (!dtTreePos.contains(adj) && world.getBlockState(adj).getBlock() instanceof BlockTrunkShell) { shellPos.add(adj); }
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
                    int rootCap = Config.view(world).getMaxTreeSize();
                    int rootsFound = 0;
                    while (!rootQueue.isEmpty() && rootsFound < rootCap) {
                        BlockPos rp = rootQueue.poll();
                        for (BlockPos side : BlastPlasterUtil.NEIGHBOR_POSITIONS) {
                            BlockPos adj = rp.add(side);
                            if (!rootVisited.add(adj)) { continue; }
                            if (dtTreePos.contains(adj) || affectedPos.contains(adj)) { continue; }
                            if (world.getBlockState(adj).getBlock() instanceof BlockSurfaceRoot) {
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
                                    BlockPos candidate = branch.add(dx, dy, dz);
                                    if (dtTreePos.contains(candidate) || affectedPos.contains(candidate)) { continue; }
                                    IBlockState candidateState = world.getBlockState(candidate);
                                    if (TreeHelper.isLeaves(candidateState) || candidateState.getBlock() instanceof BlockFruit || candidateState.getBlock() instanceof BlockFruitCocoa) { extraLeaves.add(candidate); }
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
                    if (totalBlocks > Config.view(world).getMaxTreeSize()) {
                        BlastPlaster.debug("Skipped huge DT tree expansion ({} blocks > max {})", totalBlocks, Config.view(world).getMaxTreeSize());
                        continue;
                    }

                    for (BlockPos p : dtTreePos) {
                        if (!affectedPos.contains(p)) {
                            toHeal.add(new BlockStatePosWrapper(world, p, world.getBlockState(p)));
                            affectedPos.add(p);
                        }
                    }

                    for (BlockPos p : filteredLeaves) {
                        if (!affectedPos.contains(p)) {
                            toHeal.add(new BlockStatePosWrapper(world, p, world.getBlockState(p)));
                            affectedPos.add(p);
                        }
                    }
                }
            }

            int severed = collectSeveredDynamicWood(toHeal, affectedPos, world, Collections.emptySet());
            if (severed > 0) {
                didDtExpansion = true;
                BlastPlaster.debug("Collected {} severed DT tree blocks via connectivity crawl", severed);
            }
        }

        addHugeMushrooms(toHeal, affectedPos, world);

        if (!isDtLoaded || !didDtExpansion) {
            Set<String> logKeysFound = new HashSet<>();
            for (BlockStatePosWrapper w : toHeal) {
                String logKey = Config.getLogKey(w.getState());
                if (logKey != null) { logKeysFound.add(logKey); }
            }
            BlastPlaster.debug("Vanilla expansion: destroyed log types {}", logKeysFound);

            for (String logKey : logKeysFound) {
                String leafKey = Config.getLeavesKeyForLog(logKey);
                if (leafKey == null) {
                    BlastPlaster.debug("Vanilla expansion: no leaf pairing for {}", logKey);
                    continue;
                }

                Set<BlockPos> logSeeds = new HashSet<>();
                for (BlockStatePosWrapper w : toHeal) {
                    if (logKey.equals(Config.getLogKey(w.getState()))) { logSeeds.add(w.getPos()); }
                }

                if (logSeeds.isEmpty()) { continue; }

                List<Set<BlockPos>> trunkClusters = findConnectedLogClusters(logSeeds);

                for (Set<BlockPos> cluster : trunkClusters) {
                    int originalSize = cluster.size();
                    Set<BlockPos> seedSet = cluster;
                    if (originalSize == 1) { seedSet = augmentVertical(cluster, world, logKey); }
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
                            BlockPos adj = pos.add(side);
                            if (visited.contains(adj)) { continue; }
                            IBlockState adjState = world.getBlockState(adj);
                            int newDist = dist + 1;
                            if (newDist > TREE_DISTANCE_CAP) { continue; }

                            if (logKey.equals(Config.getLogKey(adjState))) {
                                visited.add(adj);
                                openSet.add(adj);
                                allLogs.add(adj);
                                distanceMap.put(adj, newDist);
                            }
                            else if (leafKey.equals(Config.getLeavesKey(adjState))) {
                                if (!isPersistentLeaf(adjState)) {
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
                                    BlockPos adj = logPos.add(dx, dy, dz);
                                    if (visited.contains(adj)) { continue; }
                                    IBlockState adjState = world.getBlockState(adj);
                                    if (leafKey.equals(Config.getLeavesKey(adjState)) && !isPersistentLeaf(adjState)) {
                                        visited.add(adj);
                                        extraLeaves.add(adj);
                                    }
                                }
                            }
                        }
                    }

                    int maxLeafSteps = MAX_LEAF_DISTANCE_FOR_CHECK - LEAF_BRUTE_RADIUS;
                    Deque<BlockPos> leafQueue = new ArrayDeque<>(extraLeaves);
                    Map<BlockPos, Integer> leafDepth = new HashMap<>();
                    for (BlockPos seed : extraLeaves) { leafDepth.put(seed, 0); }
                    while (!leafQueue.isEmpty()) {
                        BlockPos leafPos = leafQueue.poll();
                        int depth = leafDepth.get(leafPos);
                        if (depth >= maxLeafSteps) { continue; }
                        for (BlockPos side : BlastPlasterUtil.NEIGHBOR_POSITIONS) {
                            BlockPos adj = leafPos.add(side);
                            if (visited.contains(adj)) { continue; }
                            IBlockState adjState = world.getBlockState(adj);
                            if (!leafKey.equals(Config.getLeavesKey(adjState))) { continue; }
                            if (isPersistentLeaf(adjState)) { continue; }
                            visited.add(adj);
                            extraLeaves.add(adj);
                            leafDepth.put(adj, depth + 1);
                            leafQueue.add(adj);
                        }
                    }

                    Set<BlockPos> filteredLeaves = new HashSet<>();
                    for (BlockPos leaf : extraLeaves) {
                        int dOur = minManhattanToSet(leaf, allLogs);
                        if (!hasCloserForeignLog(leaf, world, logKey, allLogs, dOur)) { filteredLeaves.add(leaf); }
                    }
                    extraLeaves = filteredLeaves;

                    int destroyedLeafCount = 0;
                    for (BlockStatePosWrapper w : toHeal) {
                        if (leafKey.equals(Config.getLeavesKey(w.getState()))) { destroyedLeafCount++; }
                    }

                    int extraLeafCount = extraLeaves.size();
                    int evidenceLeaves = extraLeafCount + destroyedLeafCount;
                    int extraLogCount = allLogs.size() - clusterSize;
                    int totalExtra = extraLogCount + extraLeafCount;
                    float leafLogRatio = allLogs.isEmpty() ? 0f : (float) evidenceLeaves / allLogs.size();

                    float confidence = calculateTreeConfidence(allLogs, extraLeaves, world);

                    if (allLogs.size() + extraLeaves.size() > Config.view(world).getMaxTreeSize()) {
                        BlastPlaster.debug("Skipped huge vanilla tree cluster ({} blocks > max {})", allLogs.size() + extraLeaves.size(), Config.view(world).getMaxTreeSize());
                        continue;
                    }

                    if (evidenceLeaves < Math.max(4, (int) (clusterSize * MIN_LEAVES_PER_SEED))
                            || extraLogCount > MAX_EXTRA_LOGS
                            || totalExtra > MAX_EXTRA_BLOCKS
                            || leafLogRatio < MIN_LEAF_LOG_RATIO
                            || confidence < MIN_TREE_CONFIDENCE
                            || isHollowStructure(allLogs, world)) {
                        BlastPlaster.debug("Vanilla expansion: cluster of {} rejected for {} (logs {}, evidence leaves {}, ratio {}, confidence {}, hollow {})", clusterSize, logKey, allLogs.size(), evidenceLeaves, leafLogRatio, confidence, isHollowStructure(allLogs, world));
                        continue;
                    }
                    BlastPlaster.debug("Vanilla expansion: cluster of {} accepted for {} ({} logs, {} leaves)", clusterSize, logKey, allLogs.size(), extraLeaves.size());

                    for (BlockPos p : allLogs) {
                        if (!affectedPos.contains(p)) {
                            toHeal.add(new BlockStatePosWrapper(world, p, world.getBlockState(p)));
                            affectedPos.add(p);
                        }
                    }
                    for (BlockPos p : extraLeaves) {
                        if (!affectedPos.contains(p)) {
                            toHeal.add(new BlockStatePosWrapper(world, p, world.getBlockState(p)));
                            affectedPos.add(p);
                        }
                    }
                }
            }
        }

        if (Config.view(world).healFullTrees()) { addConnectedVines(toHeal, affectedPos, world); }
    }

    private void addHugeMushrooms(List<BlockStatePosWrapper> toHeal, Set<BlockPos> affectedPos, World world) {
        Deque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        for (BlockStatePosWrapper w : new ArrayList<>(toHeal)) {
            if (w.getState().getBlock() instanceof BlockHugeMushroom) {
                if (visited.add(w.getPos())) { queue.add(w.getPos()); }
            }
        }
        if (queue.isEmpty()) { return; }

        Set<BlockPos> extras = new HashSet<>();
        int cap = Config.view(world).getMaxTreeSize();
        while (!queue.isEmpty() && extras.size() < cap) {
            BlockPos pos = queue.poll();
            for (BlockPos side : BlastPlasterUtil.NEIGHBOR_POSITIONS) {
                BlockPos adj = pos.add(side);
                if (!visited.add(adj)) { continue; }
                if (affectedPos.contains(adj)) { continue; }
                if (world.getBlockState(adj).getBlock() instanceof BlockHugeMushroom) {
                    extras.add(adj);
                    queue.add(adj);
                }
            }
        }

        if (!extras.isEmpty()) { BlastPlaster.debug("Huge mushroom expansion added {} blocks", extras.size()); }
        for (BlockPos p : extras) {
            if (!affectedPos.contains(p)) {
                toHeal.add(new BlockStatePosWrapper(world, p, world.getBlockState(p)));
                affectedPos.add(p);
            }
        }
    }

    public int collectSeveredDynamicWood(List<BlockStatePosWrapper> toHeal, Set<BlockPos> affectedPos, World world, Set<BlockPos> persistentWoodSeeds) {
        if (!BlastPlasterUtil.DT_LOADED) { return 0; }
        Deque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();

        for (BlockStatePosWrapper w : new ArrayList<>(toHeal)) {
            if (isDynamicWood(w.getState())) {
                queue.add(w.getPos());
                visited.add(w.getPos());
            }
        }
        for (BlockPos seed : persistentWoodSeeds) {
            if (visited.add(seed)) { queue.add(seed); }
        }
        if (queue.isEmpty()) { return 0; }

        Set<BlockPos> severedWood = new HashSet<>();
        int cap = Config.view(world).getMaxTreeSize();

        while (!queue.isEmpty() && severedWood.size() < cap) {
            BlockPos pos = queue.poll();
            for (BlockPos side : BlastPlasterUtil.NEIGHBOR_POSITIONS) {
                BlockPos adj = pos.add(side);
                if (!visited.add(adj)) { continue; }
                IBlockState adjState = world.getBlockState(adj);
                if (affectedPos.contains(adj) || persistentWoodSeeds.contains(adj)) {
                    if (persistentWoodSeeds.contains(adj) || isDynamicWood(adjState)) { queue.add(adj); }
                    continue;
                }
                if (isDynamicWood(adjState)) {
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
                        BlockPos candidate = wood.add(dx, dy, dz);
                        if (severedWood.contains(candidate) || affectedPos.contains(candidate) || attachments.contains(candidate)) { continue; }
                        IBlockState cs = world.getBlockState(candidate);
                        if (TreeHelper.isLeaves(cs) || cs.getBlock() instanceof BlockFruit || cs.getBlock() instanceof BlockFruitCocoa) { attachments.add(candidate); }
                    }
                }
            }
        }

        int added = 0;
        for (BlockPos p : severedWood) {
            if (!affectedPos.contains(p)) {
                toHeal.add(new BlockStatePosWrapper(world, p, world.getBlockState(p)));
                affectedPos.add(p);
                added++;
            }
        }
        for (BlockPos p : attachments) {
            if (!affectedPos.contains(p)) {
                toHeal.add(new BlockStatePosWrapper(world, p, world.getBlockState(p)));
                affectedPos.add(p);
                added++;
            }
        }
        return added;
    }

    private boolean isDynamicWood(IBlockState state) {
        Block block = state.getBlock();
        return TreeHelper.isBranch(state) || block instanceof BlockTrunkShell || block instanceof BlockRooty || block instanceof BlockSurfaceRoot;
    }

    private Set<BlockPos> collectUniqueDynamicRoots(List<BlockStatePosWrapper> toHeal, Set<BlockPos> affectedPos, World world) {
        Set<BlockPos> uniqueRoots = new HashSet<>();
        for (BlockPos pos : affectedPos) { tryAddDynamicRoot(pos, world, uniqueRoots); }

        for (BlockStatePosWrapper w : toHeal) {
            if (!isDynamicTreePart(w.getState())) { continue; }
            BlockPos p = w.getPos();
            for (int dx = -LEAF_BRUTE_RADIUS; dx <= LEAF_BRUTE_RADIUS; dx++) {
                for (int dy = -LEAF_BRUTE_RADIUS; dy <= LEAF_BRUTE_RADIUS; dy++) {
                    for (int dz = -LEAF_BRUTE_RADIUS; dz <= LEAF_BRUTE_RADIUS; dz++) {
                        BlockPos adj = p.add(dx, dy, dz);
                        if (affectedPos.contains(adj)) { continue; }
                        tryAddDynamicRoot(adj, world, uniqueRoots);
                    }
                }
            }
        }

        if (uniqueRoots.isEmpty()) {
            for (BlockPos p : affectedPos) {
                for (int dx = -LEAF_BRUTE_RADIUS; dx <= LEAF_BRUTE_RADIUS; dx++) {
                    for (int dy = -LEAF_BRUTE_RADIUS; dy <= LEAF_BRUTE_RADIUS; dy++) {
                        for (int dz = -LEAF_BRUTE_RADIUS; dz <= LEAF_BRUTE_RADIUS; dz++) { tryAddDynamicRoot(p.add(dx, dy, dz), world, uniqueRoots); }
                    }
                }
            }
        }
        return uniqueRoots;
    }

    private void tryAddDynamicRoot(BlockPos pos, World world, Set<BlockPos> uniqueRoots) {
        if (!BlastPlasterUtil.DT_LOADED) { return; }
        IBlockState s = world.getBlockState(pos);
        if (!isDynamicTreePart(s)) { return; }
        if (s.getBlock() instanceof BlockFruit || s.getBlock() instanceof BlockFruitCocoa) { return; }
        if (s.getBlock() instanceof BlockTrunkShell || s.getBlock() instanceof BlockSurfaceRoot) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) { continue; }
                    BlockPos corePos = pos.add(dx, 0, dz);
                    if (TreeHelper.isBranch(world.getBlockState(corePos))) {
                        BlockPos rootPos = TreeHelper.findRootNode(world, corePos);
                        if (!rootPos.equals(BlockPos.ORIGIN)) { uniqueRoots.add(rootPos.toImmutable()); }
                        return;
                    }
                }
            }
            return;
        }
        BlockPos rootPos = (TreeHelper.isBranch(s) || s.getBlock() instanceof BlockRooty) ? TreeHelper.findRootNode(world, pos) : findRootFromLeaf(world, pos);
        if (!rootPos.equals(BlockPos.ORIGIN)) { uniqueRoots.add(rootPos.toImmutable()); }
    }

    private boolean isDynamicTreePart(IBlockState state) {
        if (!BlastPlasterUtil.DT_LOADED) { return false; }
        Block block = state.getBlock();
        return TreeHelper.isBranch(state) || TreeHelper.isLeaves(state) || block instanceof BlockRooty
                || block instanceof BlockTrunkShell || block instanceof BlockSurfaceRoot || block instanceof BlockFruit || block instanceof BlockFruitCocoa;
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
                    BlockPos adj = pos.add(side);
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

    private Set<BlockPos> augmentVertical(Set<BlockPos> cluster, World world, String logKey) {
        if (cluster.size() != 1) { return cluster; }
        BlockPos start = cluster.iterator().next();
        Set<BlockPos> vertical = new HashSet<>();
        vertical.add(start);

        BlockPos p = start;
        while (true) {
            BlockPos next = p.up();
            if (!logKey.equals(Config.getLogKey(world.getBlockState(next)))) { break; }
            vertical.add(next);
            p = next;
            if (vertical.size() > TREE_DISTANCE_CAP) { break; }
        }

        p = start;
        while (true) {
            BlockPos next = p.down();
            if (!logKey.equals(Config.getLogKey(world.getBlockState(next)))) { break; }
            vertical.add(next);
            p = next;
            if (vertical.size() > TREE_DISTANCE_CAP) { break; }
        }

        return vertical;
    }

    private float calculateTreeConfidence(Set<BlockPos> allLogs, Set<BlockPos> extraLeaves, World world) {
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
                IBlockState belowState = world.getBlockState(log.down());
                Block below = belowState.getBlock();
                if (below == Blocks.DIRT || below == Blocks.GRASS || below == Blocks.MYCELIUM || BlastPlasterUtil.isDynamicTrees(belowState)) {
                    hasGrounding = true;
                    break;
                }
            }
        }

        int upperYThreshold = maxY - (int) (height * 0.4);
        int upperLeaves = 0;
        for (BlockPos leaf : extraLeaves) {
            if (leaf.getY() >= upperYThreshold) { upperLeaves++; }
        }
        float verticalBias = extraLeaves.isEmpty() ? 0f : (float) upperLeaves / extraLeaves.size();

        float aspect = getAspect(allLogs, height);

        boolean isPure = !hasArtificialStructuresNearby(allLogs, world);

        float confidence = 0.35f;
        if (hasGrounding) { confidence += 0.25f; }
        if (verticalBias >= 0.55f) { confidence += 0.2f; }
        if (aspect >= 1.55f) { confidence += 0.15f; }
        if (isPure) { confidence += 0.2f; }

        return Math.min(1.0f, confidence);
    }

    private static float getAspect(Set<BlockPos> allLogs, int height) {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BlockPos p : allLogs) {
            int x = p.getX();
            int z = p.getZ();
            if (x < minX) { minX = x; }
            if (x > maxX) { maxX = x; }
            if (z < minZ) { minZ = z; }
            if (z > maxZ) { maxZ = z; }
        }
        int width = Math.max(maxX - minX + 1, maxZ - minZ + 1);
        return height / (float) Math.max(width, 1);
    }

    private boolean hasArtificialStructuresNearby(Set<BlockPos> allLogs, World world) {
        for (BlockPos log : allLogs) {
            for (int dx = -4; dx <= 4; dx++) {
                for (int dy = -4; dy <= 4; dy++) {
                    for (int dz = -4; dz <= 4; dz++) {
                        if (isArtificialWood(world.getBlockState(log.add(dx, dy, dz)))) { return true; }
                    }
                }
            }
        }
        return false;
    }

    private boolean isArtificialWood(IBlockState state) {
        Block block = state.getBlock();
        if (block == Blocks.PLANKS) { return true; }
        if (state.getMaterial() != Material.WOOD) { return false; }
        return block instanceof BlockFence || block instanceof BlockStairs || block instanceof BlockSlab;
    }

    private boolean isJungleLog(IBlockState state) { return state.getBlock() == Blocks.LOG && state.getValue(BlockOldLog.VARIANT) == BlockPlanks.EnumType.JUNGLE; }

    private int minManhattanToSet(BlockPos target, Set<BlockPos> set) {
        int min = Integer.MAX_VALUE;
        for (BlockPos p : set) { min = Math.min(min, Math.abs(target.getX() - p.getX()) + Math.abs(target.getY() - p.getY()) + Math.abs(target.getZ() - p.getZ())); }
        return min;
    }

    private boolean hasCloserForeignLog(BlockPos leaf, World world, String logKey, Set<BlockPos> ourLogs, int ourDist) {
        int budget = ourDist - 1;
        if (budget < 0) { return false; }
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (int dx = -Math.min(8, budget); dx <= Math.min(8, budget); dx++) {
            int spanY = Math.min(8, budget - Math.abs(dx));
            for (int dy = -spanY; dy <= spanY; dy++) {
                int spanZ = Math.min(8, budget - Math.abs(dx) - Math.abs(dy));
                for (int dz = -spanZ; dz <= spanZ; dz++) {
                    probe.setPos(leaf.getX() + dx, leaf.getY() + dy, leaf.getZ() + dz);
                    if (ourLogs.contains(probe)) { continue; }
                    if (logKey.equals(Config.getLogKey(world.getBlockState(probe)))) { return true; }
                }
            }
        }
        return false;
    }

    private boolean isHollowStructure(Set<BlockPos> allLogs, World world) {
        Map<Integer, List<BlockPos>> logsByY = new HashMap<>();
        for (BlockPos p : allLogs) {
            List<BlockPos> slice = logsByY.computeIfAbsent(p.getY(), k -> new ArrayList<>());
            slice.add(p);
        }

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
            boolean roofed = hasSolidNonLeafRoof(world, x, column.getValue(), z);
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

    private boolean hasSolidNonLeafRoof(World world, int x, int topY, int z) {
        for (int dy = 1; dy <= 4; dy++) {
            IBlockState state = world.getBlockState(new BlockPos(x, topY + dy, z));
            if (!state.getMaterial().blocksMovement()) { continue; }
            if (state.getBlock() instanceof BlockLeaves) { return false; }
            return Config.getLeavesKey(state) == null;
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
                for (EnumFacing dir : new EnumFacing[] { EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.EAST, EnumFacing.WEST }) {
                    BlockPos next = cur.offset(dir);
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

    private void addConnectedVines(List<BlockStatePosWrapper> toHeal, Set<BlockPos> affectedPos, World world) {
        Set<BlockPos> vineSeeds = new HashSet<>();
        for (BlockStatePosWrapper w : new ArrayList<>(toHeal)) {
            BlockPos p = w.getPos();
            IBlockState s = world.getBlockState(p);
            if (Config.isLog(s) || s.getBlock() instanceof BlockLeaves || (BlastPlasterUtil.DT_LOADED && TreeHelper.isLeaves(s))) {
                for (BlockPos offset : BlastPlasterUtil.NEIGHBOR_POSITIONS) {
                    BlockPos adj = p.add(offset);
                    if (!affectedPos.contains(adj)) {
                        IBlockState adjState = world.getBlockState(adj);
                        if (adjState.getBlock() instanceof BlockVine) { vineSeeds.add(adj); }
                    }
                }
            }
        }
        for (BlockStatePosWrapper w : toHeal) {
            if (w.getState().getBlock() instanceof BlockVine) { vineSeeds.add(w.getPos()); }
        }
        if (vineSeeds.isEmpty()) { return; }

        Set<BlockPos> allVines = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>(vineSeeds);
        Set<BlockPos> visited = new HashSet<>(vineSeeds);

        while (!queue.isEmpty()) {
            BlockPos p = queue.poll();
            allVines.add(p);
            for (BlockPos offset : BlastPlasterUtil.NEIGHBOR_POSITIONS) {
                BlockPos adj = p.add(offset);
                if (visited.add(adj)) {
                    if (world.getBlockState(adj).getBlock() instanceof BlockVine) { queue.add(adj); }
                }
            }
        }

        for (BlockPos p : allVines) {
            if (!affectedPos.contains(p)) {
                toHeal.add(new BlockStatePosWrapper(world, p, world.getBlockState(p)));
                affectedPos.add(p);
            }
        }
    }

    private boolean isPersistentLeaf(IBlockState state) {
        if (state.getPropertyKeys().contains(BlockLeaves.DECAYABLE)) { return !state.getValue(BlockLeaves.DECAYABLE); }
        return false;
    }

    private int getLeafDistance(IBlockState state) {
        if (BlastPlasterUtil.DT_LOADED && TreeHelper.isLeaves(state)) {
            if (state.getPropertyKeys().contains(BlockDynamicLeaves.HYDRO)) { return 5 - state.getValue(BlockDynamicLeaves.HYDRO); }
            return 7;
        }
        return 0;
    }

    private BlockPos findRootFromLeaf(World world, BlockPos leafPos) {
        if (!BlastPlasterUtil.DT_LOADED) { return BlockPos.ORIGIN; }
        IBlockState state = world.getBlockState(leafPos);
        if (!TreeHelper.isLeaves(state)) { return BlockPos.ORIGIN; }
        int currentDist = getLeafDistance(state);
        BlockPos pos = leafPos;
        Set<BlockPos> visited = new HashSet<>();
        while (currentDist > 1) {
            visited.add(pos);
            int minNeighDist = currentDist;
            BlockPos nextPos = null;
            for (EnumFacing dir : EnumFacing.values()) {
                BlockPos adj = pos.offset(dir);
                if (visited.contains(adj)) { continue; }
                int neighDist = getLeafDistance(world.getBlockState(adj));
                if (neighDist > 0 && neighDist < minNeighDist) {
                    minNeighDist = neighDist;
                    nextPos = adj;
                }
            }
            if (nextPos == null) { return BlockPos.ORIGIN; }
            pos = nextPos;
            currentDist = minNeighDist;
        }
        for (EnumFacing dir : EnumFacing.values()) {
            BlockPos adj = pos.offset(dir);
            if (TreeHelper.isBranch(world.getBlockState(adj))) { return TreeHelper.findRootNode(world, adj); }
        }
        return BlockPos.ORIGIN;
    }

    private void heal(BlockStatePosWrapper blockData) {
        BlockPos pos = blockData.getPos();
        IBlockState restoreState = blockData.getState();
        Block block = restoreState.getBlock();
        IBlockState currentState = world.getBlockState(pos);

        if (currentState.equals(restoreState)) { return; }

        if (block == Blocks.REEDS) {
            restore(pos, restoreState, blockData);
            return;
        }

        if (BlastPlasterUtil.DT_LOADED && isDynamicTreePart(restoreState)) {
            restore(pos, restoreState, blockData);
            return;
        }

        if (block instanceof BlockLeaves && restoreState.getPropertyKeys().contains(BlockLeaves.CHECK_DECAY) && restoreState.getValue(BlockLeaves.CHECK_DECAY)) {
            restoreState = restoreState.withProperty(BlockLeaves.CHECK_DECAY, Boolean.FALSE);
        }

        boolean isEmpty = currentState.getMaterial() == Material.AIR;
        boolean hasFluid = currentState.getMaterial().isLiquid();

        if (Config.view(world).isOverride() || isEmpty || hasFluid) { restore(pos, restoreState, blockData); }
    }

    private void restore(BlockPos pos, IBlockState state, BlockStatePosWrapper blockData) {
        world.setBlockState(pos, state, 3);
        if (blockData.getEntityTag() != null) {
            TileEntity te = world.getTileEntity(pos);
            if (te != null) { te.readFromNBT(blockData.getEntityTag()); }
        }
    }

    @Override @Nonnull public NBTTagCompound writeToNBT(@Nonnull NBTTagCompound tag) {
        NBTTagList tagList = new NBTTagList();
        for (TickContainer<Collection<BlockStatePosWrapper>> tc : healTask.getQueue()) {
            NBTTagCompound tcTag = new NBTTagCompound();
            tcTag.setInteger("ticks", tc.getTicks());
            NBTTagList bdList = new NBTTagList();
            for (BlockStatePosWrapper bd : tc.getValue()) {
                NBTTagCompound bdTag = new NBTTagCompound();
                bd.writeNBT(bdTag);
                bdList.appendTag(bdTag);
            }
            tcTag.setTag("blockDataList", bdList);
            tagList.appendTag(tcTag);
        }
        tag.setTag("healTaskList", tagList);
        return tag;
    }

    @Override public void readFromNBT(NBTTagCompound tag) {
        NBTTagList tagList = tag.getTagList("healTaskList", Constants.NBT.TAG_COMPOUND);
        int cumulative = 0;
        int restored = 0;
        for (int i = 0; i < tagList.tagCount(); i++) {
            NBTTagCompound tcTag = tagList.getCompoundTagAt(i);
            cumulative += tcTag.getInteger("ticks");
            NBTTagList bdListTag = tcTag.getTagList("blockDataList", Constants.NBT.TAG_COMPOUND);
            for (int b = 0; b < bdListTag.tagCount(); b++) {
                BlockStatePosWrapper bd = new BlockStatePosWrapper();
                bd.readNBT(bdListTag.getCompoundTagAt(b));
                healTask.enqueue(Math.max(1, cumulative), bd);
                restored++;
            }
        }
        if (restored > 0) {
            markDirty();
            BlastPlaster.logger.info("Restored heal queue: {} blocks resuming over {} ticks", restored, cumulative);
        }
    }

    public static WorldHealerSaveDataSupplier loadWorldHealer(WorldServer world) {
        MapStorage storage = world.getPerWorldStorage();
        WorldHealerSaveDataSupplier data = (WorldHealerSaveDataSupplier) storage.getOrLoadData(WorldHealerSaveDataSupplier.class, DATAKEY);
        if (data == null) {
            data = new WorldHealerSaveDataSupplier(DATAKEY);
            storage.setData(DATAKEY, data);
        }
        data.world = world;
        return data;
    }

    @Override public boolean isDirty() { return super.isDirty() || !healTask.getQueue().isEmpty(); }

    private static final class CollectorNode implements INodeInspector {

        private final Set<BlockPos> nodeSet;

        private CollectorNode(Set<BlockPos> nodeSet) { this.nodeSet = nodeSet; }

        @Override public boolean run(IBlockState state, World world, BlockPos pos, EnumFacing fromDir) {
            nodeSet.add(pos.toImmutable());
            return true;
        }

        @Override public boolean returnRun(IBlockState state, World world, BlockPos pos, EnumFacing fromDir) { return false; }
    }
}
