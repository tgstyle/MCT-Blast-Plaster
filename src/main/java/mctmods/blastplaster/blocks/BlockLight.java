package mctmods.blastplaster.blocks;

import mctmods.blastplaster.BlastPlaster;
import mctmods.blastplaster.Config;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;

import javax.annotation.Nonnull;

@SuppressWarnings("deprecation") public class BlockLight extends Block {

    public BlockLight() {
        super(Material.AIR);
        setRegistryName(BlastPlaster.MODID, "light");
        setTranslationKey(BlastPlaster.MODID + ".light");
        setBlockUnbreakable();
        setResistance(6000000.0F);
    }

    @Override public int getLightValue(@Nonnull IBlockState state, @Nonnull IBlockAccess world, @Nonnull BlockPos pos) { return Config.view(world).getExplosionFlashLightLevel(); }

    @Override public boolean isAir(@Nonnull IBlockState state, @Nonnull IBlockAccess world, @Nonnull BlockPos pos) { return true; }

    @Override public boolean canCollideCheck(@Nonnull IBlockState state, boolean hitIfLiquid) { return false; }

    @Override public boolean isOpaqueCube(@Nonnull IBlockState state) { return false; }

    @Override public boolean isFullCube(@Nonnull IBlockState state) { return false; }

    @Override public AxisAlignedBB getCollisionBoundingBox(@Nonnull IBlockState state, @Nonnull IBlockAccess world, @Nonnull BlockPos pos) { return NULL_AABB; }

    @Override @Nonnull public EnumBlockRenderType getRenderType(@Nonnull IBlockState state) { return EnumBlockRenderType.INVISIBLE; }
}
