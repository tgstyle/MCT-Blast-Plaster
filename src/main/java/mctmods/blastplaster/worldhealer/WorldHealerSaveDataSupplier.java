package mctmods.blastplaster.worldhealer;

import java.util.*;
import java.util.function.Supplier;

import mctmods.blastplaster.Config;
import mctmods.blastplaster.BlastPlaster;
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
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraftforge.fml.ModList;

import com.ferreusveritas.dynamictrees.api.TreeHelper;
import com.ferreusveritas.dynamictrees.api.network.MapSignal;

import org.jetbrains.annotations.NotNull;

public class WorldHealerSaveDataSupplier extends SavedData implements Supplier<Object> {

  private Level level;
  private final TickingHealList healTask = new TickingHealList();
  private boolean dirtyFlag = false;
  private int lastQueueSize = 0;
  static final String DATAKEY = BlastPlaster.MODID;

  private static final int MAX_LEAF_DISTANCE = 10;

  public WorldHealerSaveDataSupplier() {}

  public void onTick() {
    if (healTask.getQueue().isEmpty()) return;
    int currentSize = healTask.getQueue().size();
    if (currentSize != lastQueueSize) {
      lastQueueSize = currentSize;
    }
    Collection<BlockStatePosWrapper> blocksToHeal = healTask.processTick();
    if (blocksToHeal != null) {
      for (BlockStatePosWrapper blockData : blocksToHeal) {
        heal(blockData);
      }
      dirtyFlag = true;
    }
  }

  public void prepareAndScheduleHealing(List<BlockStatePosWrapper> toHeal, Set<BlockPos> initialAffected, Level level) {
    if (toHeal.isEmpty()) return;

    if (Config.healFullTrees()) {
      addExtraTreeBlocks(toHeal, initialAffected, level);
    }

    List<BlockStatePosWrapper> plantExtras = new ArrayList<>();
    for (BlockStatePosWrapper w : toHeal) {
      BlockPos pos = w.getPos();
      int height = 1;
      while (true) {
        BlockPos above = pos.above(height);
        BlockState aboveState = level.getBlockState(above);
        if (aboveState.isAir() || !(aboveState.getBlock() instanceof BushBlock)) break;
        if (!initialAffected.contains(above)) {
          plantExtras.add(new BlockStatePosWrapper(level, above, aboveState));
        }
        height++;
        if (height > 16) break;
      }
    }
    toHeal.addAll(plantExtras);

    TreeMap<Integer, List<BlockStatePosWrapper>> layers = new TreeMap<>();
    for (BlockStatePosWrapper wrapper : toHeal) {
      int y = wrapper.getPos().getY();
      layers.computeIfAbsent(y, k -> new ArrayList<>()).add(wrapper);
    }

    int currentDelay = Config.getMinimumTicksBeforeHeal();
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
    dirtyFlag = true;
  }

  private void addExtraTreeBlocks(List<BlockStatePosWrapper> toHeal, Set<BlockPos> initialAffected, Level level) {
    boolean isDtLoaded = ModList.get().isLoaded("dynamictrees");
    if (isDtLoaded) {
      Set<BlockPos> uniqueRoots = new HashSet<>();
      for (BlockPos pos : initialAffected) {
        BlockState state = level.getBlockState(pos);
        if (TreeHelper.isBranch(state) || TreeHelper.isLeaves(state)) {
          BlockPos rootPos = TreeHelper.isBranch(state) ? TreeHelper.findRootNode(level, pos) : findRootFromLeaf(level, pos);
          if (rootPos != BlockPos.ZERO) {
            uniqueRoots.add(rootPos);
          }
        }
      }
      for (BlockPos rootPos : uniqueRoots) {
        Set<BlockPos> dtTreePos = new HashSet<>();
        CollectorNode branchCollector = new CollectorNode(dtTreePos);
        TreeHelper.startAnalysisFromRoot(level, rootPos, new MapSignal(branchCollector));
        Map<BlockPos, Integer> levels = new HashMap<>();
        Queue<BlockPos> leafQueue = new LinkedList<>();
        for (BlockPos branch : dtTreePos) {
          levels.put(branch, 0);
          leafQueue.add(branch);
        }
        Set<BlockPos> visited = new HashSet<>(dtTreePos);
        while (!leafQueue.isEmpty()) {
          BlockPos p = leafQueue.poll();
          int level_p = levels.get(p);
          for (Direction dir : Direction.values()) {
            BlockPos adj = p.relative(dir);
            if (!visited.contains(adj)) {
              BlockState adjState = level.getBlockState(adj);
              if (TreeHelper.isLeaves(adjState)) {
                int new_level = level_p + 1;
                if (new_level <= MAX_LEAF_DISTANCE) {
                  int old_level = levels.getOrDefault(adj, Integer.MAX_VALUE);
                  if (new_level < old_level) {
                    levels.put(adj, new_level);
                    leafQueue.add(adj);
                  }
                  dtTreePos.add(adj);
                  visited.add(adj);
                }
              }
            }
          }
        }
        for (BlockPos p : dtTreePos) {
          if (!initialAffected.contains(p)) {
            toHeal.add(new BlockStatePosWrapper(level, p, level.getBlockState(p)));
          }
        }
      }
    } else {
      Set<TagKey<Block>> logTagsFound = new HashSet<>();
      Set<Block> leafBlocksFound = new HashSet<>();
      for (BlockStatePosWrapper w : toHeal) {
        BlockState s = w.getState();
        if (s.is(BlockTags.LOGS)) {
          for (TagKey<Block> tag : Config.getTreeMap().keySet()) {
            if (s.is(tag)) {
              logTagsFound.add(tag);
              break;
            }
          }
        } else if (s.getBlock() instanceof LeavesBlock) {
          leafBlocksFound.add(s.getBlock());
        }
      }
      TagKey<Block> logTag = null;
      Block leafBlock = null;
      if (logTagsFound.size() == 1) {
        logTag = logTagsFound.iterator().next();
        leafBlock = Config.getTreeMap().get(logTag);
      } else if (logTagsFound.isEmpty() && leafBlocksFound.size() == 1) {
        leafBlock = leafBlocksFound.iterator().next();
        logTag = Config.getLeafToLogMap().get(leafBlock);
      }
      if (logTag != null && leafBlock != null) {
        Queue<BlockPos> queue = new LinkedList<>();
        Set<BlockPos> logPos = new HashSet<>();
        for (BlockStatePosWrapper w : toHeal) {
          BlockState s = w.getState();
          if (s.is(logTag)) {
            queue.add(w.getPos());
            logPos.add(w.getPos());
          } else if (s.getBlock() == leafBlock) {
            queue.add(w.getPos());
          }
        }
        Set<BlockPos> treePos = new HashSet<>();
        while (!queue.isEmpty()) {
          BlockPos pos = queue.poll();
          if (treePos.contains(pos)) continue;
          treePos.add(pos);
          for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
              for (int dz = -3; dz <= 3; dz++) {
                if (dx == 0 && dy == 0 && dz == 0) continue;
                BlockPos adj = pos.offset(dx, dy, dz);
                BlockState adjState = level.getBlockState(adj);
                if (adjState.is(logTag)) {
                  queue.add(adj);
                  logPos.add(adj);
                } else if (adjState.getBlock() == leafBlock) {
                  queue.add(adj);
                }
              }
            }
          }
        }
        if (treePos.size() > Config.getMaxTreeSize()) {
          return;
        }
        Map<BlockPos, Integer> levels = new HashMap<>();
        Queue<BlockPos> leafQueue = new LinkedList<>();
        for (BlockPos log : logPos) {
          levels.put(log, 0);
          leafQueue.add(log);
        }
        Set<BlockPos> visited = new HashSet<>(logPos);
        while (!leafQueue.isEmpty()) {
          BlockPos p = leafQueue.poll();
          int level_p = levels.get(p);
          for (Direction dir : Direction.values()) {
            BlockPos adj = p.relative(dir);
            if (!visited.contains(adj)) {
              BlockState adjState = level.getBlockState(adj);
              if (adjState.getBlock() == leafBlock) {
                int new_level = level_p + 1;
                if (new_level <= 7) {
                  int old_level = levels.getOrDefault(adj, Integer.MAX_VALUE);
                  if (new_level < old_level) {
                    levels.put(adj, new_level);
                    leafQueue.add(adj);
                  }
                  treePos.add(adj);
                  visited.add(adj);
                }
              }
            }
          }
        }
        for (BlockPos pos : treePos) {
          if (!initialAffected.contains(pos)) {
            BlockState state = level.getBlockState(pos);
            toHeal.add(new BlockStatePosWrapper(level, pos, state));
          }
        }
      }
    }
  }

  private int getLeafDistance(BlockState state) {
    if (TreeHelper.isLeaves(state)) {
      IntegerProperty hydroProp = (IntegerProperty) state.getBlock().getStateDefinition().getProperty("hydro");
      if (hydroProp != null) {
        int hydro = state.getValue(hydroProp);
        return 5 - hydro;
      } else {
        return state.getValue(LeavesBlock.DISTANCE);
      }
    }
    return 0;
  }

  private BlockPos findRootFromLeaf(Level world, BlockPos leafPos) {
    BlockState state = world.getBlockState(leafPos);
    if (!TreeHelper.isLeaves(state)) return BlockPos.ZERO;
    int currentDist = getLeafDistance(state);
    BlockPos pos = leafPos;
    Set<BlockPos> visited = new HashSet<>();
    while (currentDist > 1) {
      visited.add(pos);
      int minNeighDist = currentDist;
      BlockPos nextPos = null;
      for (Direction dir : Direction.values()) {
        BlockPos adj = pos.relative(dir);
        if (visited.contains(adj)) continue;
        BlockState adjState = world.getBlockState(adj);
        int neighDist = getLeafDistance(adjState);
        if (neighDist > 0 && neighDist < minNeighDist) {
          minNeighDist = neighDist;
          nextPos = adj;
        }
      }
      if (nextPos == null) return BlockPos.ZERO;
      pos = nextPos;
      currentDist = minNeighDist;
    }
    for (Direction dir : Direction.values()) {
      BlockPos adj = pos.relative(dir);
      BlockState adjState = world.getBlockState(adj);
      if (TreeHelper.isBranch(adjState)) {
        return TreeHelper.findRootNode(world, adj);
      }
    }
    return BlockPos.ZERO;
  }

  private void heal(BlockStatePosWrapper blockData) {
    BlockPos pos = blockData.getPos();
    BlockState currentState = level.getBlockState(pos);
    FluidState fluid = level.getFluidState(pos);
    boolean isEmpty = currentState.isAir();
    boolean hasFluid = !fluid.isEmpty();
    if (Config.isOverride() || isEmpty || hasFluid) {
      if ((!isEmpty && !hasFluid) && Config.isDropIfAlreadyBlock()) {
        dropBlockData(blockData);
      }
      level.setBlock(pos, blockData.getState(), 3);
      if (blockData.getEntityTag() != null) {
        BlockEntity te = level.getBlockEntity(pos);
        if (te != null) {
          te.load(blockData.getEntityTag());
        }
      }
    } else if (Config.isDropIfAlreadyBlock()) {
      dropBlockData(blockData);
    }
  }

  private void dropBlockData(BlockStatePosWrapper blockData) {
    Block block = blockData.getState().getBlock();
    BlastPlasterUtil.dropItemWithMotion(level, blockData.getPos(), new ItemStack(block), 0.05F);
    CompoundTag tag = blockData.getEntityTag();
    if (tag != null && block instanceof EntityBlock) {
      BlockEntity te = ((EntityBlock) block).newBlockEntity(blockData.getPos(), blockData.getState());
      if (te instanceof Container ct) {
        te.load(tag);
        Containers.dropContents(level, blockData.getPos(), ct);
      }
    }
  }

  @Override
  public @NotNull CompoundTag save(@NotNull CompoundTag tag) {
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
    return tag;
  }

  public void deserializeNBT(CompoundTag tag) {
    ListTag tagList = tag.getList("healTaskList", Tag.TAG_COMPOUND);
    List<BlockStatePosWrapper> allWrappers = new ArrayList<>();
    for (Tag t : tagList) {
      CompoundTag tcTag = (CompoundTag) t;
      ListTag bdListTag = tcTag.getList("blockDataList", Tag.TAG_COMPOUND);
      for (Tag bt : bdListTag) {
        CompoundTag bdTag = (CompoundTag) bt;
        BlockStatePosWrapper bd = new BlockStatePosWrapper();
        bd.readNBT(bdTag, level);
        allWrappers.add(bd);
      }
    }
    if (!allWrappers.isEmpty()) {
      TreeMap<Integer, List<BlockStatePosWrapper>> layers = new TreeMap<>();
      for (BlockStatePosWrapper wrapper : allWrappers) {
        int y = wrapper.getPos().getY();
        layers.computeIfAbsent(y, k -> new ArrayList<>()).add(wrapper);
      }
      for (List<BlockStatePosWrapper> layer : layers.values()) {
        for (BlockStatePosWrapper blockData : layer) {
          heal(blockData);
        }
      }
      dirtyFlag = true;
    }
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

  @Override
  public boolean isDirty() {
    boolean d = dirtyFlag;
    dirtyFlag = false;
    return d || !healTask.getQueue().isEmpty();
  }

  @Override
  public Object get() {
    return this;
  }

  private record CollectorNode(Set<BlockPos> nodeSet) implements com.ferreusveritas.dynamictrees.api.network.NodeInspector {

    @Override
    public boolean run(BlockState state, LevelAccessor level, BlockPos pos, Direction fromDir) {
      nodeSet.add(pos.immutable());
      return false;
    }

    @Override
    public boolean returnRun(BlockState state, LevelAccessor level, BlockPos pos, Direction fromDir) {
      return false;
    }
  }
}
