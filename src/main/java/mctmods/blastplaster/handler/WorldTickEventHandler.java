package mctmods.blastplaster.handler;

import mctmods.blastplaster.BlastPlaster;
import mctmods.blastplaster.worldhealer.WorldHealerSaveDataSupplier;

import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public class WorldTickEventHandler {

    public WorldTickEventHandler() { MinecraftForge.EVENT_BUS.register(this); }

    @SubscribeEvent public void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.world.isRemote) { return; }
        if (event.phase != TickEvent.Phase.END) { return; }
        if (!(event.world instanceof WorldServer)) { return; }
        WorldServer world = (WorldServer) event.world;
        ExplosionEventHandler.runDelayedTasks(world);
        WorldHealerSaveDataSupplier worldHealer = BlastPlaster.getWorldHealer(world);
        if (worldHealer != null) { worldHealer.onTick(); }
    }
}
