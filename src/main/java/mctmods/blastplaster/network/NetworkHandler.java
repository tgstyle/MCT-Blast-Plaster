package mctmods.blastplaster.network;

import mctmods.blastplaster.BlastPlaster;

import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

public class NetworkHandler {

    public static final SimpleNetworkWrapper INSTANCE = NetworkRegistry.INSTANCE.newSimpleChannel(BlastPlaster.MODID);
    private static final double PARTICLE_RANGE = 128.0D;

    private NetworkHandler() {}

    public static void init() {
        INSTANCE.registerMessage(PacketFlash.Handler.class, PacketFlash.class, 0, Side.CLIENT);
        INSTANCE.registerMessage(PacketSmoke.Handler.class, PacketSmoke.class, 1, Side.CLIENT);
    }

    public static void sendFlash(WorldServer world, double x, double y, double z, int count, double spread) {
        INSTANCE.sendToAllAround(new PacketFlash(x, y, z, count, spread), new NetworkRegistry.TargetPoint(world.provider.getDimension(), x, y, z, PARTICLE_RANGE));
    }

    public static void sendSmoke(WorldServer world, double x, double y, double z, int count, double spreadXZ, double spreadY, double speed) {
        INSTANCE.sendToAllAround(new PacketSmoke(x, y, z, count, spreadXZ, spreadY, speed), new NetworkRegistry.TargetPoint(world.provider.getDimension(), x, y, z, PARTICLE_RANGE));
    }
}
