package mctmods.blastplaster.helper;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.Level;
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
        if (entity != null) {
            this.entityTag = entity.saveWithoutMetadata();
        }
    }

    public BlockStatePosWrapper() {
    }

    public BlockState getState() {
        return this.state;
    }

    public BlockPos getPos() {
        return this.pos;
    }

    public CompoundTag getEntityTag() {
        return this.entityTag;
    }

    public void readNBT(CompoundTag tag, Level level) {
        this.state = NbtUtils.readBlockState(level.holderLookup(Registries.BLOCK), tag.getCompound("block"));
        this.pos = NbtUtils.readBlockPos(tag.getCompound("pos"));
        if (tag.contains("entity")) {
            this.entityTag = tag.getCompound("entity");
        }
    }

    public void writeNBT(CompoundTag tag) {
        tag.put("block", NbtUtils.writeBlockState(this.state));
        tag.put("pos", NbtUtils.writeBlockPos(this.pos));
        if (this.entityTag != null) {
            tag.put("entity", this.entityTag);
        }
    }
}
