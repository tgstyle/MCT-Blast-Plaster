package mctmods.blastplaster.worldhealer;

import mctmods.blastplaster.BlastPlaster;
import mctmods.blastplaster.Config;
import mctmods.blastplaster.helper.BlockStatePosWrapper;
import mctmods.blastplaster.helper.MultiPhaseTickingHealList;
import mctmods.blastplaster.helper.MultiPhaseTickingHealList.HealPhase;
import mctmods.blastplaster.helper.TickContainer;
import mctmods.blastplaster.util.BlastPlasterUtil;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
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
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;

import net.neoforged.fml.ModList;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

import com.dtteam.dynamictrees.tree.TreeHelper;
import com.dtteam.dynamictrees.block.soil.SoilBlock;
import com.dtteam.dynamictrees.block.soil.SpeciesBlockEntity;
import com.dtteam.dynamictrees.tree.species.Species;
import com.dtteam.dynamictrees.block.branch.BasicRootsBlock;

public class WorldHealerSaveDataSupplier extends SavedData implements java.util.function.Supplier<Object> {

  private Level level;
  private final MultiPhaseTickingHealList healTask = new MultiPhaseTickingHealList();
  private boolean dirtyFlag = false;
  static final String DATAKEY = BlastPlaster.MODID;

  public WorldHealerSaveDataSupplier() {}

  public void onTick() {
    if (healTask.isEmpty()) { return; }
    Collection<BlockStatePosWrapper> blocksToHeal = healTask.processTick();
    if (blocksToHeal != null && !blocksToHeal.isEmpty()) {
      BlastPlaster.LOGGER.info("[BlastPlaster] onTick processing {} blocks this tick", blocksToHeal.size());
      for (BlockStatePosWrapper blockData : blocksToHeal) { heal(blockData); }
      dirtyFlag = true;
    }
  }

  public void prepareAndScheduleHealing(List<BlockStatePosWrapper> toHeal, Level level) {
    if (toHeal.isEmpty()) { return; }

    List<BlockStatePosWrapper> dtParts = new ArrayList<>();
    if (ModList.get().isLoaded("dynamictrees")) {
      for (int i = toHeal.size() - 1; i >= 0; i--) {
        BlockStatePosWrapper w = toHeal.get(i);
        BlockState state = w.getState();
        if (BlastPlasterUtil.isDynamicTrees(state)) {
          dtParts.add(w);
          toHeal.remove(i);
        }
      }
    }

    BlastPlaster.LOGGER.info("[BlastPlaster] DT PARTS collected: {}", dtParts.size());

    if (!dtParts.isEmpty() && ModList.get().isLoaded("dynamictrees")) {
      dtParts.sort(Comparator.comparingInt((BlockStatePosWrapper w) -> w.getPos().getY()).reversed());
    }

    int currentDelay = Config.getMinimumTicksBeforeHeal();

    List<BlockStatePosWrapper> ground = new ArrayList<>();
    List<BlockStatePosWrapper> vines = new ArrayList<>();
    List<BlockStatePosWrapper> flora = new ArrayList<>();
    List<BlockStatePosWrapper> bambooCane = new ArrayList<>();

    for (BlockStatePosWrapper w : toHeal) {
      BlockState state = w.getState();
      Block block = state.getBlock();

      if (state.getBlock() instanceof VineBlock) {
        vines.add(w);
      } else if (block == Blocks.BAMBOO || block == Blocks.SUGAR_CANE) {
        bambooCane.add(w);
      } else if (state.is(BlockTags.FLOWERS) || block == Blocks.SHORT_GRASS || block == Blocks.TALL_GRASS || block == Blocks.FERN || block == Blocks.LARGE_FERN || block == Blocks.DEAD_BUSH || block == Blocks.SWEET_BERRY_BUSH) {
        flora.add(w);
      } else {
        ground.add(w);
      }
    }

    BlastPlaster.LOGGER.info("[BlastPlaster] GROUND collected: {}, FLORA: {}, VINES: {}, BAMBOO_CANE: {}", ground.size(), flora.size(), vines.size(), bambooCane.size());

    TreeMap<Integer, List<BlockStatePosWrapper>> layers = new TreeMap<>();
    for (BlockStatePosWrapper wrapper : ground) {
      int y = wrapper.getPos().getY();
      layers.computeIfAbsent(y, k -> new ArrayList<>()).add(wrapper);
    }

    int var = Config.getRandomTickVar();
    for (List<BlockStatePosWrapper> layer : layers.values()) {
      int layerDelay = currentDelay;
      if (layer.size() == 1) {
        healTask.enqueue(HealPhase.GROUND, layerDelay, layer.getFirst());
        currentDelay += 20;
      } else {
        for (BlockStatePosWrapper wrapper : layer) {
          int delay = layerDelay + level.random.nextInt(var);
          healTask.enqueue(HealPhase.GROUND, delay, wrapper);
        }
        currentDelay += var;
      }
    }

    if (!bambooCane.isEmpty()) {
      int batchTick = currentDelay + 10;
      for (BlockStatePosWrapper item : bambooCane) { healTask.enqueue(HealPhase.TREE, batchTick, item); }
      currentDelay = batchTick + 5;
    }

    if (ModList.get().isLoaded("dynamictrees") && !dtParts.isEmpty()) {
      int dtBatchTick = currentDelay + 10;
      for (BlockStatePosWrapper w : dtParts) {
        healTask.enqueue(HealPhase.TREE, dtBatchTick, w);
      }
      currentDelay = dtBatchTick + 10;
    }

    if (!flora.isEmpty()) {
      flora.sort((a, b) -> Integer.compare(b.getPos().getY(), a.getPos().getY()));
      int floraStart = currentDelay + 25;
      for (BlockStatePosWrapper f : flora) {
        healTask.enqueue(HealPhase.FLORA, floraStart, f);
        floraStart += 8;
      }
    }

    if (!vines.isEmpty()) {
      vines.sort((a, b) -> Integer.compare(b.getPos().getY(), a.getPos().getY()));
      int vineDelay = currentDelay + 40;
      for (BlockStatePosWrapper vine : vines) {
        healTask.enqueue(HealPhase.FLORA, vineDelay, vine);
        vineDelay += 12;
      }
    }

    dirtyFlag = true;
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

    BlastPlaster.LOGGER.info("[BlastPlaster] heal() called for block={} at {}", restoreState.getBlock(), pos);

    if (ModList.get().isLoaded("dynamictrees")) {
      logNonLeafDTBlocks(blockData);
    }

    Block block = restoreState.getBlock();
    if (block == Blocks.BAMBOO || block == Blocks.SUGAR_CANE) {
      level.setBlock(pos, restoreState, 3);
      if (blockData.getEntityTag() != null) {
        BlockEntity te = level.getBlockEntity(pos);
        if (te != null) { te.loadCustomOnly(blockData.getEntityTag(), level.registryAccess()); }
      }
      return;
    }

    if (ModList.get().isLoaded("dynamictrees") && TreeHelper.getTreePart(restoreState) != TreeHelper.NULL_TREE_PART) {
      level.setBlock(pos, restoreState, 3);
      level.updateNeighborsAt(pos, restoreState.getBlock());

      BlastPlaster.LOGGER.info("[BlastPlaster] DT block healed at {}", pos);

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

  private void logNonLeafDTBlocks(BlockStatePosWrapper blockData) {
    BlockState state = blockData.getState();
    BlockPos pos = blockData.getPos();
    if (!ModList.get().isLoaded("dynamictrees")) { return; }
    if (TreeHelper.getTreePart(state) == TreeHelper.NULL_TREE_PART) { return; }
    if (TreeHelper.isLeaves(state)) { return; }

    String partType = "UNKNOWN_DT_PART";
    String blockKey = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().toLowerCase();
    String descId = state.getBlock().getDescriptionId().toLowerCase();

    if (state.getBlock() instanceof BasicRootsBlock || blockKey.contains("root") || descId.contains("root") || TreeHelper.isRooty(state)) {
      partType = "ROOT_BLOCK";
    } else if (TreeHelper.isBranch(state)) {
      partType = "BRANCH";
    } else if (TreeHelper.isRooty(state)) {
      partType = "ROOTY";
    }

    int radius = BlastPlasterUtil.getDTRadius(state);
    BlastPlaster.LOGGER.info("[BlastPlaster] NON-LEAF DT BLOCK: type={} pos={} radius={}", partType, pos, radius);
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
    BlastPlaster.LOGGER.info("[BlastPlaster] deserializeNBT called");
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
      for (BlockStatePosWrapper w : allWrappers) {
        BlockState state = w.getState();
        if (state.getBlock() instanceof VineBlock) {
          healTask.enqueue(HealPhase.FLORA, 1, w);
        } else if (state.getBlock() == Blocks.BAMBOO || state.getBlock() == Blocks.SUGAR_CANE || (ModList.get().isLoaded("dynamictrees") && (TreeHelper.isBranch(state) || TreeHelper.isLeaves(state) || TreeHelper.getRooty(state) != null))) {
          healTask.enqueue(HealPhase.TREE, 1, w);
        } else if (state.is(BlockTags.FLOWERS) || state.getBlock() == Blocks.SHORT_GRASS || state.getBlock() == Blocks.TALL_GRASS || state.getBlock() == Blocks.FERN || state.getBlock() == Blocks.LARGE_FERN || state.getBlock() == Blocks.DEAD_BUSH || state.getBlock() == Blocks.SWEET_BERRY_BUSH) {
          healTask.enqueue(HealPhase.FLORA, 1, w);
        } else {
          healTask.enqueue(HealPhase.GROUND, 1, w);
        }
      }
      dirtyFlag = true;
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
    return d || !healTask.isEmpty();
  }

  @Override public Object get() { return this; }
}
