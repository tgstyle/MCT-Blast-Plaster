package mctmods.blastplaster.handler;

import mctmods.blastplaster.worldhealer.WorldHealerSaveDataSupplier;

import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import java.util.HashMap;
import java.util.Map;

public class WorldEventHandler {

    private final Map<World, WorldHealerSaveDataSupplier> worldHealers = new HashMap<>();

    public WorldEventHandler() { MinecraftForge.EVENT_BUS.register(this); }

    public Map<World, WorldHealerSaveDataSupplier> getWorldHealers() { return worldHealers; }

    @SubscribeEvent public void onLoad(WorldEvent.Load event) {
        World world = event.getWorld();
        if (world.isRemote || !(world instanceof WorldServer)) { return; }
        worldHealers.put(world, WorldHealerSaveDataSupplier.loadWorldHealer((WorldServer) world));
    }

    @SubscribeEvent public void onUnload(WorldEvent.Unload event) {
        World world = event.getWorld();
        if (world.isRemote || !(world instanceof WorldServer)) { return; }
        ExplosionEventHandler.flushWorld((WorldServer) world);
        worldHealers.remove(world);
    }
}
