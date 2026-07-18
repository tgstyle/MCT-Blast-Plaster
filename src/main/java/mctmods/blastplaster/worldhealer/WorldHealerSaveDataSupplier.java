package mctmods.blastplaster.worldhealer;

import mctmods.blastplaster.BlastPlaster;
import mctmods.blastplaster.Config;
import mctmods.blastplaster.helper.BlockStatePosWrapper;
import mctmods.blastplaster.helper.TickContainer;
import mctmods.blastplaster.helper.TickingHealList;
import mctmods.blastplaster.util.BlastPlasterUtil;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.jetbrains.annotations.NotNull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import com.dtteam.dynamictrees.tree.TreeHelper;
import com.dtteam.dynamictrees.block.branch.BasicRootsBlock;
import com.dtteam.dynamictrees.block.branch.SurfaceRootBlock;
import com.dtteam.dynamictrees.block.branch.TrunkShellBlock;
import com.dtteam.dynamictrees.block.fruit.FruitBlock;
import com.dtteam.dynamictrees.block.pod.PodBlock;
import com.dtteam.dynamictrees.block.soil.SoilBlock;
import com.dtteam.dynamictrees.block.soil.SpeciesBlockEntity;
import com.dtteam.dynamictrees.tree.species.Species;

public class WorldHealerSaveDataSupplier extends SavedData {

  private Level level;
  private final TickingHealList healTask = new TickingHealList();
  private boolean dirtyFlag = false;
  static final String DATAKEY = BlastPlaster.MODID;

  public WorldHealerSaveDataSupplier() {}

  public void onTick() {
    if (healTask.getQueue().isEmpty()) { return; }
    Collection<BlockStatePosWrapper> blocksToHeal = healTask.processTick();
    if (blocksToHeal != null && !blocksToHeal.isEmpty()) {
      if (Config.debugLogging()) {
        BlockStatePosWrapper first = blocksToHeal.iterator().next();
        BlastPlaster.LOGGER.info("Heal batch released: {} blocks at gameTime {} (first: {} at {})", blocksToHeal.size(), level.getGameTime(), first.getState().getBlock().getClass().getSimpleName(), first.getPos());
      }
      for (BlockStatePosWrapper blockData : blocksToHeal) { heal(blockData); }
      dirtyFlag = true;
    }
  }

  public void prepareAndScheduleHealing(List<BlockStatePosWrapper> toHeal) {
    if (toHeal.isEmpty()) { return; }

    int currentDelay = Config.getMinimumTicksBeforeHeal();
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
        Block b = w.getState().getBlock();
        if (b instanceof SurfaceRootBlock) {
          dtSurfaceRoots.add(w);
          toHeal.remove(i);
          continue;
        }
        if (b instanceof BasicRootsBlock) {
          dtRoots.add(w);
          toHeal.remove(i);
          continue;
        }
        if (TreeHelper.isBranch(w.getState())) {
          dtBranches.add(w);
          toHeal.remove(i);
          continue;
        }
        if (b instanceof TrunkShellBlock) {
          dtShells.add(w);
          toHeal.remove(i);
          continue;
        }
        if (TreeHelper.isLeaves(w.getState())) {
          dtLeaves.add(w);
          toHeal.remove(i);
          continue;
        }
        if (b instanceof FruitBlock || b instanceof PodBlock) {
          dtFruitPods.add(w);
          toHeal.remove(i);
        }
      }
    }

    List<BlockStatePosWrapper> ground = new ArrayList<>();
    List<BlockStatePosWrapper> vanillaLeaves = new ArrayList<>();
    List<BlockStatePosWrapper> vines = new ArrayList<>();
    List<BlockStatePosWrapper> flora = new ArrayList<>();
    List<BlockStatePosWrapper> bambooCane = new ArrayList<>();

    for (BlockStatePosWrapper w : toHeal) {
      BlockState state = w.getState();
      Block block = state.getBlock();

      if (block instanceof VineBlock) { vines.add(w); }
      else if (block == Blocks.BAMBOO || block == Blocks.SUGAR_CANE) { bambooCane.add(w); }
      else if (block instanceof LeavesBlock) { vanillaLeaves.add(w); }
      else if (state.is(BlockTags.FLOWERS) || block == Blocks.SHORT_GRASS || block == Blocks.TALL_GRASS || block == Blocks.FERN || block == Blocks.LARGE_FERN || block == Blocks.DEAD_BUSH || block == Blocks.SWEET_BERRY_BUSH) { flora.add(w); }
      else { ground.add(w); }
    }

    int groundEnd = scheduleLayeredHealing(ground, currentDelay);

    int pairBase = groundEnd + 4;
    for (BlockStatePosWrapper w : dtPriority) {
      int tick = (w.getState().getBlock() instanceof SoilBlock) ? pairBase : pairBase + 2;
      healTask.enqueue(tick, w);
    }

    int woodTick = pairBase + 6;
    int leavesTick = woodTick + 12;
    int fruitTick = leavesTick + 8;
    int floraTick = fruitTick + 6;

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

    if (!flora.isEmpty()) {
      flora.sort((a, b) -> Integer.compare(b.getPos().getY(), a.getPos().getY()));
      int floraStep = Math.clamp(160 / flora.size(), 1, 8);
      int floraDelay = floraTick;
      for (BlockStatePosWrapper f : flora) {
        healTask.enqueue(floraDelay, f);
        floraDelay += floraStep;
      }
    }

    if (!woodBatch.isEmpty() || !leafBatch.isEmpty() || !dtPriority.isEmpty()) {
      BlastPlaster.debug("Heal timeline: ground ends {}, {} soil pairs at {}, {} wood at {}, {} surface roots at {}, {} leaves at {}, {} fruit/pods at {}", groundEnd, dtPriority.size(), pairBase, woodBatch.size(), woodTick, dtSurfaceRoots.size(), woodTick + 4, leafBatch.size(), leavesTick, dtFruitPods.size(), fruitTick);
    }

    if (!vines.isEmpty()) {
      vines.sort((a, b) -> Integer.compare(b.getPos().getY(), a.getPos().getY()));
      int vineDelay = leavesTick + 80;
      int vineStep = Math.clamp(240 / vines.size(), 1, 12);
      for (BlockStatePosWrapper vine : vines) {
        healTask.enqueue(vineDelay, vine);
        vineDelay += vineStep;
      }
    }

    dirtyFlag = true;
  }

  private int scheduleLayeredHealing(List<BlockStatePosWrapper> blocks, int baseDelay) {
    if (blocks.isEmpty()) { return baseDelay; }
    int currentDelay = baseDelay;

    TreeMap<Integer, List<BlockStatePosWrapper>> layers = new TreeMap<>();
    for (BlockStatePosWrapper wrapper : blocks) {
      layers.computeIfAbsent(wrapper.getPos().getY(), ignored -> new ArrayList<>()).add(wrapper);
    }

    int var = Config.getRandomTickVar();
    for (List<BlockStatePosWrapper> layer : layers.values()) {
      int layerDelay = currentDelay;
      if (layer.size() == 1) {
        healTask.enqueue(layerDelay, layer.getFirst());
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
    Set<BlockPos> toRemove = new java.util.HashSet<>();
    Set<BlockPos> seenRoots = new java.util.HashSet<>();

    Map<BlockPos, BlockStatePosWrapper> byPos = new HashMap<>();
    for (BlockStatePosWrapper w : toHeal) { byPos.put(w.getPos(), w); }

    for (BlockStatePosWrapper w : new ArrayList<>(toHeal)) {
      if (w.getState().getBlock() instanceof SoilBlock) {
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

  public void addMultiBlockStructures(List<BlockStatePosWrapper> toHeal, Set<BlockPos> affectedPos, Level level) {
    List<BlockStatePosWrapper> extras = new ArrayList<>();
    for (BlockStatePosWrapper w : new ArrayList<>(toHeal)) {
      BlockPos pos = w.getPos();
      BlockState state = w.getState();
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

  private void heal(BlockStatePosWrapper blockData) {
    BlockPos pos = blockData.getPos();
    BlockState restoreState = blockData.getState();

    Block block = restoreState.getBlock();
    if (block == Blocks.BAMBOO || block == Blocks.SUGAR_CANE) {
      level.setBlock(pos, restoreState, 3);
      if (blockData.getEntityTag() != null) {
        BlockEntity te = level.getBlockEntity(pos);
        if (te != null) { te.loadCustomOnly(blockData.getEntityTag(), level.registryAccess()); }
      }
      return;
    }

    if (BlastPlasterUtil.DT_LOADED && TreeHelper.getTreePart(restoreState) != TreeHelper.NULL_TREE_PART) {
      level.setBlock(pos, restoreState, 3);
      level.updateNeighborsAt(pos, restoreState.getBlock());

      if (blockData.getEntityTag() != null) {
        BlockEntity te = level.getBlockEntity(pos);
        if (te != null) { te.loadCustomOnly(blockData.getEntityTag(), level.registryAccess()); }
      }

      if (restoreState.getBlock() instanceof SoilBlock soil) {
        BlockPos trunkPos = pos.relative(soil.getTrunkDirection(level, pos));
        level.updateNeighborsAt(trunkPos, level.getBlockState(trunkPos).getBlock());
        level.scheduleTick(pos, restoreState.getBlock(), 1);
        BlockEntity te = level.getBlockEntity(pos);
        if (te instanceof SpeciesBlockEntity sbe) {
          if (sbe.getSpecies() == Species.NULL_SPECIES || !sbe.getSpecies().isValid()) {
            Species guess = TreeHelper.getBestGuessSpecies(level, pos);
            if (guess.isValid()) {
              sbe.setSpecies(guess);
            }
          }
        }
      }
      return;
    }

    BlockState currentState = level.getBlockState(pos);
    if (currentState.equals(restoreState)) { return; }

    if (restoreState.getBlock() instanceof LeavesBlock
            && restoreState.hasProperty(LeavesBlock.DISTANCE)
            && restoreState.hasProperty(LeavesBlock.PERSISTENT)
            && !restoreState.getValue(LeavesBlock.PERSISTENT)
            && restoreState.getValue(LeavesBlock.DISTANCE) >= 7) {
      restoreState = restoreState.setValue(LeavesBlock.DISTANCE, 6);
    }

    FluidState fluid = level.getFluidState(pos);
    boolean isEmpty = currentState.isAir();
    boolean hasFluid = !fluid.isEmpty();

    if (Config.isOverride() || isEmpty || hasFluid) {
      level.setBlock(pos, restoreState, 3);
      if (blockData.getEntityTag() != null) {
        BlockEntity te = level.getBlockEntity(pos);
        if (te != null) { te.loadCustomOnly(blockData.getEntityTag(), level.registryAccess()); }
      }
    }
  }

  @Override @NotNull public CompoundTag save(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
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
    int cumulative = 0;
    int leadOffset = -1;
    int restored = 0;
    for (Tag t : tagList) {
      CompoundTag tcTag = (CompoundTag) t;
      cumulative += tcTag.getInt("ticks");
      if (leadOffset < 0) { leadOffset = Math.max(0, cumulative - Config.getMinimumTicksBeforeHeal()); }
      ListTag bdListTag = tcTag.getList("blockDataList", Tag.TAG_COMPOUND);
      for (Tag bt : bdListTag) {
        CompoundTag bdTag = (CompoundTag) bt;
        BlockStatePosWrapper bd = new BlockStatePosWrapper();
        bd.readNBT(bdTag, level);
        healTask.enqueue(Math.max(1, cumulative - leadOffset), bd);
        restored++;
      }
    }
    if (restored > 0) {
      dirtyFlag = true;
      BlastPlaster.LOGGER.info("[BlastPlaster] Restored heal queue: {} blocks resuming over {} ticks for {}", restored, Math.max(1, cumulative - leadOffset), level.dimension().location());
    }
  }

  public static WorldHealerSaveDataSupplier loadWorldHealer(ServerLevel serverLevelIn) {
    DimensionDataStorage storage = serverLevelIn.getDataStorage();
    return storage.computeIfAbsent(
            new SavedData.Factory<>(
                    () -> {
                      WorldHealerSaveDataSupplier w = new WorldHealerSaveDataSupplier();
                      w.level = serverLevelIn;
                      return w;
                    },
                    (tag, registries) -> {
                      WorldHealerSaveDataSupplier w = new WorldHealerSaveDataSupplier();
                      w.level = serverLevelIn;
                      w.deserializeNBT(tag);
                      return w;
                    }
            ),
            DATAKEY
    );
  }

  @Override public boolean isDirty() {
    boolean d = dirtyFlag;
    dirtyFlag = false;
    return d || !healTask.getQueue().isEmpty();
  }
}
