package mctmods.blastplaster.helper;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class BlockStatePosWrapper {
    private BlockPos pos;
    private BlockState state;
    private CompoundTag entityTag;

    public BlockStatePosWrapper(Level level, BlockPos pos, BlockState state) {
        this.state = state;
        this.pos = pos;
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity != null) { this.entityTag = entity.saveWithoutMetadata(level.registryAccess()); }
    }

    public BlockStatePosWrapper() {}

    public BlockState getState() {
        return this.state;
    }

    public BlockPos getPos() {
        return this.pos;
    }

    public void convertTo(BlockState converted) {
        this.state = converted;
        this.entityTag = null;
    }

    public void readNBT(CompoundTag tag, Level level) {
        HolderGetter<Block> blocks = (level != null)
                ? level.holderLookup(Registries.BLOCK)
                : BuiltInRegistries.BLOCK;
        this.state = tag.getCompound("block")
                .map(c -> NbtUtils.readBlockState(blocks, c))
                .orElse(Blocks.AIR.defaultBlockState());

        int[] p = tag.getIntArray("pos").orElse(new int[0]);
        this.pos = (p.length == 3) ? new BlockPos(p[0], p[1], p[2]) : BlockPos.ZERO;

        if (tag.contains("entity")) {
            this.entityTag = tag.getCompound("entity").orElse(null);
        }
    }

    public void writeNBT(CompoundTag tag) {
        tag.put("block", NbtUtils.writeBlockState(this.state));
        tag.putIntArray("pos", new int[] { this.pos.getX(), this.pos.getY(), this.pos.getZ() });
        if (this.entityTag != null) { tag.put("entity", this.entityTag); }
    }
}
