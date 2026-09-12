package mctmods.blastplaster.helper;

import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTUtil;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public class BlockStatePosWrapper {

    private BlockPos pos;
    private IBlockState state;
    private NBTTagCompound entityTag;

    public BlockStatePosWrapper(World world, BlockPos pos, IBlockState state) {
        this.state = state;
        this.pos = pos;
        TileEntity entity = world.getTileEntity(pos);
        if (entity != null) { this.entityTag = entity.writeToNBT(new NBTTagCompound()); }
    }

    public BlockStatePosWrapper() {}

    public IBlockState getState() { return this.state; }

    public BlockPos getPos() { return this.pos; }

    public void convertTo(IBlockState converted) {
        this.state = converted;
        this.entityTag = null;
    }

    public NBTTagCompound getEntityTag() { return this.entityTag; }

    public void readNBT(NBTTagCompound tag) {
        this.state = NBTUtil.readBlockState(tag.getCompoundTag("block"));
        this.pos = BlockPos.fromLong(tag.getLong("pos"));
        if (tag.hasKey("entity")) { this.entityTag = tag.getCompoundTag("entity"); }
    }

    public void writeNBT(NBTTagCompound tag) {
        tag.setTag("block", NBTUtil.writeBlockState(new NBTTagCompound(), this.state));
        tag.setLong("pos", this.pos.toLong());
        if (this.entityTag != null) { tag.setTag("entity", this.entityTag); }
    }
}
