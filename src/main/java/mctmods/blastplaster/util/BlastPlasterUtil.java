package mctmods.blastplaster.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public class BlastPlasterUtil {
    public static void dropItemWithMotion(Level level, BlockPos pos, ItemStack stack, float motionScale) {
        if (pos == null || level == null || stack.isEmpty()) {
            return;
        }
        ItemEntity item = new ItemEntity(level,
                pos.getX() + level.random.nextFloat() * 0.8F + 0.1F,
                pos.getY() + level.random.nextFloat() * 0.8F + 0.1F,
                pos.getZ() + level.random.nextFloat() * 0.8F + 0.1F,
                stack);
        item.setDeltaMovement(
                level.random.nextGaussian() * motionScale,
                level.random.nextGaussian() * motionScale + 0.2F,
                level.random.nextGaussian() * motionScale);
        level.addFreshEntity(item);
    }
}
