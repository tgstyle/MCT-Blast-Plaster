package mctmods.blastplaster.util;

import mctmods.blastplaster.Config;
import mctmods.blastplaster.helper.BlockStatePosWrapper;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.TickTask;
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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import com.ferreusveritas.dynamictrees.api.TreeHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class BlastPlasterUtil {

    public static final float DEFAULT_VISUAL_CHANCE = 1.00f;
    public static final float CREEPER_VISUAL_CHANCE = 0.25f;
    public static final float ALEXSCAVES_NUKE_VISUAL_CHANCE = 0.01f;

    public static final List<BlockPos> NEIGHBOR_POSITIONS = new ArrayList<>(26);

    static {
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    if (x != 0 || y != 0 || z != 0) { NEIGHBOR_POSITIONS.add(new BlockPos(x, y, z)); }
                }
            }
        }
    }

    public static float getVisualSpawnChance(boolean isCreeper, boolean isAlexsCavesNuke) {
        if (isAlexsCavesNuke) { return ALEXSCAVES_NUKE_VISUAL_CHANCE; }
        if (isCreeper) { return CREEPER_VISUAL_CHANCE; }
        return DEFAULT_VISUAL_CHANCE;
    }

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
        toProcess.addAll(extras);
    }

    public record PendingDrop(Vec3 pos, ItemStack stack, boolean isGentle) {}

    public static boolean isDynamicTrees(BlockState state) {
        if (!ModList.get().isLoaded("dynamictrees")) { return false; }
        return TreeHelper.isBranch(state) || TreeHelper.isLeaves(state) || TreeHelper.isRooty(state);
    }

    public static int getDTRadius(BlockState state) {
        for (Property<?> p : state.getProperties()) { if ("radius".equals(p.getName()) && p instanceof IntegerProperty radiusProp) { return state.getValue(radiusProp); } }
        return 1;
    }

    public static List<ItemStack> generateDynamicTreesDrops(ServerLevel level, BlockState state) {
        int radius = getDTRadius(state);
        List<ItemStack> drops = new ArrayList<>();

        int numLogs = 0;
        if (radius >= 8) { numLogs = 1 + level.random.nextInt(2); }
        else if (radius >= 6) { numLogs = 1; }
        else if (radius >= 4) { numLogs = level.random.nextBoolean() ? 1 : 0; }

        int numSticks = 0;
        if (TreeHelper.isLeaves(state)) { numSticks = level.random.nextFloat() < 0.05f ? 1 : 0; }

        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        String path = (key != null) ? key.toString() : "";
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

    public static void spawnDynamicTreesDrops(ServerLevel level, BlockPos pos, BlockState state) {
        List<ItemStack> drops = generateDynamicTreesDrops(level, state);
        Vec3 center = Vec3.atCenterOf(pos);
        for (ItemStack stack : drops) {
            ItemEntity item = new ItemEntity(level, center.x, center.y + 0.5, center.z, stack);
            applyTossVelocity(item, level);
            level.addFreshEntity(item);
        }
    }

    public static void spawnVisualTossedBlock(ServerLevel level, BlockPos pos, BlockState state) {
        ItemStack stack = new ItemStack(state.getBlock());
        ItemEntity visual = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack);
        visual.setPickUpDelay(32767);
        visual.lifespan = 60;
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

    public static void clearExplodedBlock(ServerLevel level, BlockPos pos) {
        level.removeBlockEntity(pos);
        level.destroyBlock(pos, false);
    }

    public static void finalizeExplodedBlock(ServerLevel level, BlockPos pos, BlockState state, Config.ExplosionMode effectiveMode, boolean realDropOccurred, float visualSpawnChance) {
        if (effectiveMode == Config.ExplosionMode.EJECT_DROPS) {
            if (!realDropOccurred && Config.enableFakeTossedBlocks() && level.random.nextFloat() < visualSpawnChance) { spawnVisualTossedBlock(level, pos, state); }
        } else if (Config.enableFakeTossedBlocks() && (effectiveMode == Config.ExplosionMode.HEAL || effectiveMode == Config.ExplosionMode.VISUAL_TOSS) && level.random.nextFloat() < visualSpawnChance) { spawnVisualTossedBlock(level, pos, state); }
        clearExplodedBlock(level, pos);
    }

    public static boolean calculateRealDrop(ServerLevel level) { return level.random.nextFloat() < (Config.enableFakeTossedBlocks() ? (1f / 3f) : 0.91F); }

    public static AABB createScavengerBox(Set<BlockPos> positions) {
        if (positions.isEmpty()) { return new AABB(0, 0, 0, 0, 0, 0); }

        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double minZ = Double.MIN_VALUE;
        double maxX = Double.MIN_VALUE;
        double maxY = Double.MIN_VALUE;
        double maxZ = Double.MIN_VALUE;

        for (BlockPos pos : positions) {
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }

        return new AABB(minX - 6.0, minY - 6.0, minZ - 6.0, maxX + 7.0, maxY + 7.0, maxZ + 7.0);
    }

    public static boolean shouldScavenge(ItemEntity item) {
        return !item.getPersistentData().getBoolean("BlastPlasterMobDrop");
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

    public static void scheduleItemScavenger(ServerLevel level, Set<BlockPos> affectedPositions) {
        if (affectedPositions.isEmpty()) { return; }

        AABB box = createScavengerBox(affectedPositions);

        int scavengerTick = level.getServer().getTickCount() + 2;
        level.getServer().tell(new TickTask(scavengerTick, () -> level.getEntitiesOfClass(ItemEntity.class, box, item -> item.getAge() < 10 && shouldScavenge(item)).forEach(ItemEntity::discard)));

        int insuranceTick = level.getServer().getTickCount() + 60;
        level.getServer().tell(new TickTask(insuranceTick, () -> level.getEntitiesOfClass(ItemEntity.class, box, item -> item.getAge() < 70 && shouldScavenge(item)).forEach(ItemEntity::discard)));
    }
}
