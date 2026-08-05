package mctmods.blastplaster.network;

import mctmods.blastplaster.BlastPlaster;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class PacketFlash implements IMessage {

    private double x;
    private double y;
    private double z;
    private double spread;
    private int count;

    public PacketFlash() {}

    public PacketFlash(double x, double y, double z, int count, double spread) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.count = count;
        this.spread = spread;
    }

    @Override public void fromBytes(ByteBuf buf) {
        this.x = buf.readDouble();
        this.y = buf.readDouble();
        this.z = buf.readDouble();
        this.count = buf.readInt();
        this.spread = buf.readDouble();
    }

    @Override public void toBytes(ByteBuf buf) {
        buf.writeDouble(this.x);
        buf.writeDouble(this.y);
        buf.writeDouble(this.z);
        buf.writeInt(this.count);
        buf.writeDouble(this.spread);
    }

    public static class Handler implements IMessageHandler<PacketFlash, IMessage> {

        @Override public IMessage onMessage(PacketFlash message, MessageContext ctx) {
            BlastPlaster.proxy.spawnFlash(message.x, message.y, message.z, message.count, message.spread);
            return null;
        }
    }
}
