package mctmods.blastplaster.handler;

import mctmods.blastplaster.worldhealer.RegionSnapshotHealer;
import mctmods.blastplaster.worldhealer.WorldHealerSaveDataSupplier;

import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import java.util.HashMap;
import java.util.Map;

public class WorldEventHandler {

  private final Map<ServerLevel, WorldHealerSaveDataSupplier> worldHealers = new HashMap<>();

  public WorldEventHandler() { MinecraftForge.EVENT_BUS.register(this); }

  public Map<ServerLevel, WorldHealerSaveDataSupplier> getWorldHealers() { return worldHealers; }

  @SubscribeEvent public void onLoad(LevelEvent.Load event) {
    if (event.getLevel().isClientSide() || !(event.getLevel() instanceof ServerLevel level)) { return; }
    worldHealers.put(level, WorldHealerSaveDataSupplier.loadWorldHealer(level));
  }

  @SubscribeEvent public void onUnload(LevelEvent.Unload event) {
    if (event.getLevel().isClientSide()) { return; }
    ServerLevel level = (ServerLevel) event.getLevel();
    RegionSnapshotHealer.onLevelUnload(level);
    worldHealers.remove(level);
  }
}
