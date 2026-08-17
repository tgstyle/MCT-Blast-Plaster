package mctmods.blastplaster.util;

import mctmods.blastplaster.BlastPlaster;
import mctmods.blastplaster.Config;
import mctmods.blastplaster.helper.BlockStatePosWrapper;

import net.minecraft.block.Block;
import net.minecraft.block.BlockOldLog;
import net.minecraft.block.BlockPlanks;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.properties.PropertyInteger;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.item.EntityFallingBlock;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import com.ferreusveritas.dynamictrees.api.TreeHelper;
import com.ferreusveritas.dynamictrees.api.treedata.ITreePart;
import com.ferreusveritas.dynamictrees.blocks.BlockBranch;
import com.ferreusveritas.dynamictrees.blocks.BlockFruit;
import com.ferreusveritas.dynamictrees.blocks.BlockFruitCocoa;
import com.ferreusveritas.dynamictrees.blocks.BlockSurfaceRoot;
import com.ferreusveritas.dynamictrees.blocks.BlockTrunkShell;
import com.ferreusveritas.dynamictrees.trees.TreeFamily;
import net.minecraftforge.common.util.FakePlayerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class BlastPlasterUtil {

    public static final float DEFAULT_VISUAL_CHANCE = 1.00f;
    public static final float CREEPER_VISUAL_CHANCE = 0.25f;
    public static final int FALLING_BLOCK_SUPPRESS_TICKS = 25;
    public static final String BYPASS_TAG = "BlastPlasterBypass";
    public static final boolean DT_LOADED = BlastPlaster.dynamictrees;
    public static final List<BlockPos> NEIGHBOR_POSITIONS = new ArrayList<>(26);
    private static final List<ExplosionArea> recentExplosions = new ArrayList<>();

    static {
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    if (x != 0 || y != 0 || z != 0) { NEIGHBOR_POSITIONS.add(new BlockPos(x, y, z)); }
                }
            }
        }
    }

    private static class ExplosionArea {

        private final int dimension;
        private final AxisAlignedBB box;
        private final long expireTick;
        private final long fallingExpireTick;

        private ExplosionArea(int dimension, AxisAlignedBB box, long expireTick, long fallingExpireTick) {
            this.dimension = dimension;
            this.box = box;
            this.expireTick = expireTick;
            this.fallingExpireTick = fallingExpireTick;
        }
    }

    public static class PendingDrop {

        private final Vec3d pos;
        private final ItemStack stack;
        private final boolean gentle;

        public PendingDrop(Vec3d pos, ItemStack stack, boolean gentle) {
            this.pos = pos;
            this.stack = stack;
            this.gentle = gentle;
        }

        public Vec3d pos() { return pos; }

        public ItemStack stack() { return stack; }

        public boolean isGentle() { return gentle; }
    }

    public static float getVisualSpawnChance(boolean isCreeper) { return isCreeper ? CREEPER_VISUAL_CHANCE : DEFAULT_VISUAL_CHANCE; }

    public static void markSuppressionBypass(EntityItem item) { item.getEntityData().setBoolean(BYPASS_TAG, true); }

    public static void recordExplosionArea(WorldServer world, Set<BlockPos> positions, boolean suppressFallingBlocks) {
        if (positions.isEmpty()) { return; }

        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;

        for (BlockPos pos : positions) {
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }

        AxisAlignedBB box = new AxisAlignedBB(minX - 15.0, minY - 15.0, minZ - 15.0, maxX + 16.0, maxY + 16.0, maxZ + 16.0);
        long now = world.getTotalWorldTime();

        recentExplosions.add(new ExplosionArea(world.provider.getDimension(), box, now + 200L, suppressFallingBlocks ? now + FALLING_BLOCK_SUPPRESS_TICKS : 0L));
        expire(now);
    }

    private static void expire(long now) {
        for (int i = recentExplosions.size() - 1; i >= 0; i--) {
            if (recentExplosions.get(i).expireTick < now) { recentExplosions.remove(i); }
        }
    }

    public static boolean shouldSuppressItemDrop(EntityItem item) {
        if (item.getEntityData().getBoolean(BYPASS_TAG)) { return false; }
        if (!(item.world instanceof WorldServer)) { return false; }
        return shouldSuppressAt((WorldServer) item.world, item.getPositionVector());
    }

    private static boolean shouldSuppressAt(WorldServer world, Vec3d pos) {
        long now = world.getTotalWorldTime();
        int dimension = world.provider.getDimension();
        expire(now);

        for (ExplosionArea area : recentExplosions) {
            if (area.dimension == dimension && area.box.contains(pos)) { return true; }
        }
        return false;
    }

    public static boolean shouldSuppressLaunchAt(WorldServer world, Vec3d pos) {
        long now = world.getTotalWorldTime();
        int dimension = world.provider.getDimension();
        expire(now);

        for (ExplosionArea area : recentExplosions) {
            if (area.dimension == dimension && area.fallingExpireTick >= now && area.box.contains(pos)) { return true; }
        }
        return false;
    }

    public static boolean shouldSuppressFallingBlock(EntityFallingBlock falling) {
        if (!(falling.world instanceof WorldServer)) { return false; }
        return shouldSuppressLaunchAt((WorldServer) falling.world, falling.getPositionVector());
    }

    private static void addVerticalInDirection(List<BlockStatePosWrapper> extras, Set<BlockPos> affectedPos, World world, BlockPos pos, Block blockType, boolean upward) {
        int h = 1;
        while (true) {
            BlockPos offset = upward ? pos.up(h) : pos.down(h);
            IBlockState state = world.getBlockState(offset);
            if (state.getBlock() != blockType) { break; }
            if (!affectedPos.contains(offset)) { extras.add(new BlockStatePosWrapper(world, offset, state)); }
            h++;
            if (h > 20) { break; }
        }
    }

    public static void addVerticalColumn(List<BlockStatePosWrapper> extras, Set<BlockPos> affectedPos, World world, BlockPos pos, Block blockType) {
        addVerticalInDirection(extras, affectedPos, world, pos, blockType, true);
        addVerticalInDirection(extras, affectedPos, world, pos, blockType, false);
    }

    public static void addReedVerticals(List<BlockStatePosWrapper> toProcess, Set<BlockPos> affectedPos, World world) {
        List<BlockStatePosWrapper> extras = new ArrayList<>();
        for (BlockStatePosWrapper w : new ArrayList<>(toProcess)) {
            BlockPos pos = w.getPos();
            Block block = w.getState().getBlock();

            if (block == Blocks.REEDS) {
                addVerticalColumn(extras, affectedPos, world, pos, block);

                for (BlockPos offset : NEIGHBOR_POSITIONS) {
                    BlockPos adj = pos.add(offset);
                    IBlockState adjState = world.getBlockState(adj);
                    if (adjState.getBlock() == block) { addVerticalColumn(extras, affectedPos, world, adj, block); }
                }
            }
        }
        toProcess.addAll(extras);
    }

    @SuppressWarnings("unused") public static boolean isTreeWood(IBlockState state) {
        if (Config.isLog(state)) { return true; }
        if (!DT_LOADED) { return false; }
        return TreeHelper.isBranch(state) || state.getBlock() instanceof BlockTrunkShell;
    }

    public static boolean isDynamicTrees(IBlockState state) {
        if (!DT_LOADED) { return false; }
        return TreeHelper.isBranch(state) || TreeHelper.isLeaves(state) || TreeHelper.isRooty(state);
    }

    public static boolean isDynamicTreesAssembly(IBlockState state) {
        if (!DT_LOADED) { return false; }
        Block block = state.getBlock();
        return TreeHelper.isBranch(state) || TreeHelper.isLeaves(state) || TreeHelper.isRooty(state)
                || block instanceof BlockTrunkShell || block instanceof BlockSurfaceRoot || block instanceof BlockFruit || block instanceof BlockFruitCocoa;
    }

    public static int getDTRadius(IBlockState state) {
        if (!DT_LOADED) { return 1; }
        ITreePart treePart = TreeHelper.getTreePart(state);
        if (treePart != TreeHelper.nullTreePart) { return treePart.getRadius(state); }
        for (IProperty<?> property : state.getPropertyKeys()) {
            if ("radius".equals(property.getName()) && property instanceof PropertyInteger) { return state.getValue((PropertyInteger) property); }
        }
        return 1;
    }

    public static List<ItemStack> generateDynamicTreesDrops(WorldServer world, IBlockState state) {
        List<ItemStack> drops = new ArrayList<>();
        if (!DT_LOADED) { return drops; }

        int radius = getDTRadius(state);
        int numLogs = 0;
        if (radius >= 8) { numLogs = 1 + world.rand.nextInt(2); }
        else if (radius >= 6) { numLogs = 1; }
        else if (radius >= 4) { numLogs = world.rand.nextBoolean() ? 1 : 0; }

        if (numLogs > 0) {
            BlockBranch branch = TreeHelper.getBranch(state);
            if (branch != null) {
                TreeFamily family = branch.getFamily();
                ItemStack logStack = family == null ? ItemStack.EMPTY : family.getPrimitiveLogItemStack(1);
                if (!logStack.isEmpty()) { for (int i = 0; i < numLogs; i++) { drops.add(logStack.copy()); }}
            }
        }

        if (TreeHelper.isLeaves(state) && world.rand.nextFloat() < 0.05f) { drops.add(new ItemStack(Items.STICK, 1)); }

        return drops;
    }

    public static void addDynamicTreesDropsToPending(List<PendingDrop> pending, WorldServer world, BlockPos pos, IBlockState state, boolean isGentle) {
        List<ItemStack> drops = generateDynamicTreesDrops(world, state);
        Vec3d center = centerOf(pos);
        for (ItemStack stack : drops) { pending.add(new PendingDrop(center, stack, isGentle)); }
    }

    public static void spawnVisualTossedBlock(WorldServer world, BlockPos pos, IBlockState state) {
        ItemStack stack;
        RayTraceResult target = new RayTraceResult(new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5), EnumFacing.UP, pos);
        try { stack = state.getBlock().getPickBlock(state, target, world, pos, FakePlayerFactory.getMinecraft(world)); }
        catch (Exception e) { stack = new ItemStack(state.getBlock()); }
        if (stack.isEmpty()) { return; }
        EntityItem visual = new EntityItem(world, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack);
        visual.setInfinitePickupDelay();
        visual.lifespan = 60;
        markSuppressionBypass(visual);
        applyTossVelocity(visual, world);
        world.spawnEntity(visual);
    }

    public static void applyTossVelocity(EntityItem entity, WorldServer world) {
        double dx = world.rand.nextDouble() - 0.5;
        double dy = world.rand.nextDouble() * 0.55 + 0.35;
        double dz = world.rand.nextDouble() - 0.5;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len > 0) {
            double strength = 0.42 + world.rand.nextDouble() * 0.58;
            entity.motionX = dx / len * strength;
            entity.motionY = dy / len * strength;
            entity.motionZ = dz / len * strength;
        }
    }

    public static void applyGentleTossVelocity(EntityItem entity, WorldServer world) {
        double dx = world.rand.nextDouble() - 0.5;
        double dy = world.rand.nextDouble() * 0.3 + 0.25;
        double dz = world.rand.nextDouble() - 0.5;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len > 0) {
            double strength = 0.18 + world.rand.nextDouble() * 0.22;
            entity.motionX = dx / len * strength;
            entity.motionY = dy / len * strength;
            entity.motionZ = dz / len * strength;
        }
    }

    public static void setDtDestroyIgnored(boolean ignored) {
        if (!DT_LOADED) { return; }
        BlockBranch.destroyMode = ignored ? BlockBranch.EnumDestroyMode.IGNORE : BlockBranch.EnumDestroyMode.SLOPPY;
    }

    public static void clearExplodedBlock(WorldServer world, BlockPos pos) {
        world.removeTileEntity(pos);
        world.destroyBlock(pos, false);
    }

    public static void finalizeExplodedBlock(WorldServer world, BlockPos pos, IBlockState state, Config.ExplosionMode effectiveMode, boolean realDropOccurred, float visualSpawnChance) {
        if (effectiveMode == Config.ExplosionMode.EJECT_DROPS) {
            if (!realDropOccurred && Config.view(world).enableFakeTossedBlocks() && world.rand.nextFloat() < visualSpawnChance) { spawnVisualTossedBlock(world, pos, state); }
        }
        else if (Config.view(world).enableFakeTossedBlocks() && (effectiveMode == Config.ExplosionMode.HEAL || effectiveMode == Config.ExplosionMode.VISUAL_TOSS) && world.rand.nextFloat() < visualSpawnChance) { spawnVisualTossedBlock(world, pos, state); }
        clearExplodedBlock(world, pos);
    }

    public static void addAttachedCocoaPods(List<BlockStatePosWrapper> toProcess, Set<BlockPos> affectedPos, WorldServer world) {
        List<BlockStatePosWrapper> extras = new ArrayList<>();
        for (BlockStatePosWrapper w : new ArrayList<>(toProcess)) {
            BlockPos pos = w.getPos();
            if (!isJungleLog(w.getState())) { continue; }

            for (EnumFacing dir : EnumFacing.values()) {
                BlockPos adj = pos.offset(dir);
                if (affectedPos.contains(adj)) { continue; }
                IBlockState adjState = world.getBlockState(adj);
                if (adjState.getBlock() == Blocks.COCOA) {
                    world.destroyBlock(adj, false);
                    extras.add(new BlockStatePosWrapper(world, adj, adjState));
                    affectedPos.add(adj);
                }
            }
        }
        toProcess.addAll(extras);
    }

    private static boolean isJungleLog(IBlockState state) { return state.getBlock() == Blocks.LOG && state.getValue(BlockOldLog.VARIANT) == BlockPlanks.EnumType.JUNGLE; }

    private static Vec3d centerOf(BlockPos pos) { return new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5); }
}
