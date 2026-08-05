package mctmods.blastplaster.network;

import mctmods.blastplaster.BlastPlaster;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class PacketSmoke implements IMessage {

    private double x;
    private double y;
    private double z;
    private int count;
    private double spreadXZ;
    private double spreadY;
    private double speed;

    public PacketSmoke() {}

    public PacketSmoke(double x, double y, double z, int count, double spreadXZ, double spreadY, double speed) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.count = count;
        this.spreadXZ = spreadXZ;
        this.spreadY = spreadY;
        this.speed = speed;
    }

    @Override public void fromBytes(ByteBuf buf) {
        this.x = buf.readDouble();
        this.y = buf.readDouble();
        this.z = buf.readDouble();
        this.count = buf.readInt();
        this.spreadXZ = buf.readDouble();
        this.spreadY = buf.readDouble();
        this.speed = buf.readDouble();
    }

    @Override public void toBytes(ByteBuf buf) {
        buf.writeDouble(this.x);
        buf.writeDouble(this.y);
        buf.writeDouble(this.z);
        buf.writeInt(this.count);
        buf.writeDouble(this.spreadXZ);
        buf.writeDouble(this.spreadY);
        buf.writeDouble(this.speed);
    }

    public static class Handler implements IMessageHandler<PacketSmoke, IMessage> {

        @Override public IMessage onMessage(PacketSmoke message, MessageContext ctx) {
            BlastPlaster.proxy.spawnSmoke(message.x, message.y, message.z, message.count, message.spreadXZ, message.spreadY, message.speed);
            return null;
        }
    }
}
