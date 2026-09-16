package mctmods.blastplaster.util;

import mctmods.blastplaster.Config;

import com.dtteam.dynamictrees.block.branch.BasicRootsBlock;
import com.dtteam.dynamictrees.block.branch.SurfaceRootBlock;
import com.dtteam.dynamictrees.block.branch.TrunkShellBlock;
import com.dtteam.dynamictrees.block.fruit.FruitBlock;
import com.dtteam.dynamictrees.block.pod.PodBlock;
import com.dtteam.dynamictrees.tree.TreeHelper;
import mctmods.blastplaster.helper.BlockStatePosWrapper;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;

import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.dtteam.dynamictrees.DynamicTrees;
import com.dtteam.dynamictrees.block.branch.BranchBlock;

@SuppressWarnings("unused")
public class BlastPlasterUtil {

    public static final float DEFAULT_VISUAL_CHANCE = 1.00f;
    public static final float CREEPER_VISUAL_CHANCE = 0.25f;
    public static final String BYPASS_TAG = "BlastPlasterControlledDrop";

    public static void markSuppressionBypass(ItemEntity item) { item.getPersistentData().putBoolean(BYPASS_TAG, true); }

    public static boolean isTreeWood(BlockState state) { return Config.isLog(state) || (DT_LOADED && (TreeHelper.isBranch(state) || state.getBlock() instanceof TrunkShellBlock)); }

    public static boolean isDynamicTreesAssembly(BlockState state) {
        if (!DT_LOADED) { return false; }
        Block block = state.getBlock();
        return TreeHelper.isBranch(state) || TreeHelper.isLeaves(state) || TreeHelper.isRooty(state)
                || block instanceof TrunkShellBlock || block instanceof SurfaceRootBlock || block instanceof BasicRootsBlock
                || block instanceof FruitBlock || block instanceof PodBlock;
    }

    public static final boolean DT_LOADED = ModList.get().isLoaded("dynamictrees");

    public static final List<BlockPos> NEIGHBOR_POSITIONS = new ArrayList<>(26);
    private static final int KNOCK_ON_WINDOW = 20;
    private static boolean suppressingDrops;
    private static final Map<ResourceKey<Level>, Map<Long, Long>> blastPositions = new HashMap<>();

    static {
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    if (x != 0 || y != 0 || z != 0) { NEIGHBOR_POSITIONS.add(new BlockPos(x, y, z)); }
                }
            }
        }
    }

    public static float getVisualSpawnChance(boolean isCreeper) {
        if (isCreeper) { return CREEPER_VISUAL_CHANCE; }
        return DEFAULT_VISUAL_CHANCE;
    }

    public static void setDropSuppression(boolean suppress) { suppressingDrops = suppress; }

    public static void recordBlastPositions(ServerLevel level, List<BlockStatePosWrapper> removed) {
        long now = level.getGameTime();
        Map<Long, Long> tracked = blastPositions.computeIfAbsent(level.dimension(), ignored -> new HashMap<>());
        tracked.values().removeIf(expire -> expire < now);
        for (BlockStatePosWrapper wrapper : removed) { tracked.put(wrapper.getPos().asLong(), now + KNOCK_ON_WINDOW); }
    }

    public static boolean knockedLooseByBlast(ServerLevel level, BlockPos pos) {
        Map<Long, Long> tracked = blastPositions.get(level.dimension());
        if (tracked == null || tracked.isEmpty()) { return false; }
        long now = level.getGameTime();
        tracked.values().removeIf(expire -> expire < now);
        boolean touching = tracked.containsKey(pos.asLong()) || NEIGHBOR_POSITIONS.stream().anyMatch(offset -> tracked.containsKey(pos.offset(offset).asLong()));
        if (touching) { tracked.put(pos.asLong(), now + KNOCK_ON_WINDOW); }
        return touching;
    }

    public static boolean shouldSuppressItemDrop(ItemEntity item) { return suppressingDrops && !item.getPersistentData().getBoolean(BYPASS_TAG); }

    public static void addVerticalColumn(List<BlockStatePosWrapper> extras, Set<BlockPos> affectedPos, Level level, BlockPos pos, Block blockType) {
        int h = 1;
        while (true) {
            BlockPos up = pos.above(h);
            BlockState upState = level.getBlockState(up);
            if (upState.getBlock() != blockType) { break; }
            if (!affectedPos.contains(up)) { extras.add(new BlockStatePosWrapper(level, up, upState)); }
            h++;
            if (h > 20) { break; }
        }

        h = 1;
        while (true) {
            BlockPos down = pos.below(h);
            BlockState downState = level.getBlockState(down);
            if (downState.getBlock() != blockType) { break; }
            if (!affectedPos.contains(down)) { extras.add(new BlockStatePosWrapper(level, down, downState)); }
            h++;
            if (h > 20) { break; }
        }
    }

    public static void addBambooVerticals(List<BlockStatePosWrapper> toProcess, Set<BlockPos> affectedPos, Level level) {
        List<BlockStatePosWrapper> extras = new ArrayList<>();
        for (BlockStatePosWrapper w : new ArrayList<>(toProcess)) {
            BlockPos pos = w.getPos();
            Block block = w.getState().getBlock();

            if (block == Blocks.SUGAR_CANE || block == Blocks.BAMBOO) {
                addVerticalColumn(extras, affectedPos, level, pos, block);

                for (BlockPos offset : NEIGHBOR_POSITIONS) {
                    BlockPos adj = pos.offset(offset);
                    BlockState adjState = level.getBlockState(adj);
                    if (adjState.getBlock() == block) { addVerticalColumn(extras, affectedPos, level, adj, block); }
                }
            }
        }
        for (BlockStatePosWrapper extra : extras) {
            if (affectedPos.add(extra.getPos())) { toProcess.add(extra); }
        }
    }

    public record PendingDrop(Vec3 pos, ItemStack stack, boolean isGentle) {}

    public static boolean isDynamicTrees(BlockState state) {
        if (!DT_LOADED) { return false; }
        if (TreeHelper.getTreePart(state) != TreeHelper.NULL_TREE_PART) {
            return true;
        }
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return "dynamictrees".equals(key.getNamespace());
    }

    public static int getDTRadius(BlockState state) {
        if (!DT_LOADED) { return 1; }
        for (Property<?> p : state.getProperties()) {
            if ("radius".equals(p.getName()) && p instanceof IntegerProperty radiusProp) {
                return state.getValue(radiusProp);
            }
        }
        return 1;
    }

    public static List<ItemStack> generateDynamicTreesDrops(ServerLevel level, BlockState state) {
        if (!DT_LOADED) { return new ArrayList<>(); }

        int radius = getDTRadius(state);
        List<ItemStack> drops = new ArrayList<>();

        int numLogs = 0;
        if (radius >= 8) { numLogs = 1 + level.random.nextInt(2); }
        else if (radius >= 6) { numLogs = 1; }
        else if (radius >= 4) { numLogs = level.random.nextBoolean() ? 1 : 0; }

        int numSticks = 0;
        if (TreeHelper.isLeaves(state)) { numSticks = level.random.nextFloat() < 0.05f ? 1 : 0; }

        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        String path = key.toString();
        Block logBlock = Config.getDTLogForPath(path);
        ItemStack logStack = new ItemStack(logBlock);

        for (int i = 0; i < numLogs; i++) { drops.add(logStack.copy()); }
        if (numSticks > 0) { drops.add(new ItemStack(Items.STICK, numSticks)); }

        return drops;
    }

    public static void addDynamicTreesDropsToPending(List<PendingDrop> pending, ServerLevel level, BlockPos pos, BlockState state, boolean isGentle) {
        List<ItemStack> drops = generateDynamicTreesDrops(level, state);
        Vec3 center = Vec3.atCenterOf(pos);
        for (ItemStack stack : drops) { pending.add(new PendingDrop(center, stack, isGentle)); }
    }


    public static void spawnVisualTossedBlock(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.isAir()) { return; }
        ItemStack stack = new ItemStack(state.getBlock());
        ItemEntity visual = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack);
        visual.setPickUpDelay(32767);
        visual.lifespan = 60;
        markSuppressionBypass(visual);
        applyTossVelocity(visual, level);
        level.addFreshEntity(visual);
    }

    public static void applyTossVelocity(ItemEntity entity, ServerLevel level) {
        double dx = level.random.nextDouble() - 0.5;
        double dy = level.random.nextDouble() * 0.55 + 0.35;
        double dz = level.random.nextDouble() - 0.5;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len > 0) {
            double strength = 0.42 + level.random.nextDouble() * 0.58;
            entity.setDeltaMovement(dx / len * strength, dy / len * strength, dz / len * strength);
        }
    }

    public static void applyGentleTossVelocity(ItemEntity entity, ServerLevel level) {
        double dx = level.random.nextDouble() - 0.5;
        double dy = level.random.nextDouble() * 0.3 + 0.25;
        double dz = level.random.nextDouble() - 0.5;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len > 0) {
            double strength = 0.18 + level.random.nextDouble() * 0.22;
            entity.setDeltaMovement(dx / len * strength, dy / len * strength, dz / len * strength);
        }
    }

    public static void setDtDestroyIgnored(boolean ignored) {
        if (!DT_LOADED) { return; }
        BranchBlock.destroyMode = ignored ? DynamicTrees.DestroyMode.IGNORE : DynamicTrees.DestroyMode.SLOPPY;
    }

    public static void clearExplodedBlock(ServerLevel level, BlockPos pos) {
        level.removeBlockEntity(pos);
        level.destroyBlock(pos, false);
    }

    public static void finalizeExplodedBlock(ServerLevel level, BlockPos pos, BlockState state, Config.ExplosionMode effectiveMode, boolean realDropOccurred, float visualSpawnChance) {
        if (effectiveMode == Config.ExplosionMode.VISUAL_TOSS) {
            if (Config.view(level).enableFakeTossedBlocks() && level.random.nextFloat() < visualSpawnChance) { spawnVisualTossedBlock(level, pos, state); }
        } else if (effectiveMode == Config.ExplosionMode.HEAL) {
            if (Config.view(level).enableFakeTossedBlocks() && level.random.nextFloat() < visualSpawnChance) { spawnVisualTossedBlock(level, pos, state); }
        } else if (effectiveMode == Config.ExplosionMode.EJECT_DROPS) {
            if (!realDropOccurred && Config.view(level).enableFakeTossedBlocks() && level.random.nextFloat() < visualSpawnChance) { spawnVisualTossedBlock(level, pos, state); }
        }
        clearExplodedBlock(level, pos);
    }

    public static void addAttachedCocoaPods(List<BlockStatePosWrapper> toProcess, Set<BlockPos> affectedPos, ServerLevel level) {
        List<BlockStatePosWrapper> extras = new ArrayList<>();
        for (BlockStatePosWrapper w : new ArrayList<>(toProcess)) {
            BlockPos pos = w.getPos();
            BlockState state = w.getState();
            if (state.getBlock() == Blocks.JUNGLE_LOG || state.getBlock() == Blocks.JUNGLE_WOOD) {
                for (Direction dir : Direction.values()) {
                    BlockPos adj = pos.relative(dir);
                    if (!affectedPos.contains(adj)) {
                        BlockState adjState = level.getBlockState(adj);
                        if (adjState.getBlock() == Blocks.COCOA) {
                            level.destroyBlock(adj, false);
                            extras.add(new BlockStatePosWrapper(level, adj, adjState));
                            affectedPos.add(adj);
                        }
                    }
                }
            }
        }
        toProcess.addAll(extras);
    }
}
