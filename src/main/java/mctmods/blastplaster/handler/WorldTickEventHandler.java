package mctmods.blastplaster.handler;

import mctmods.blastplaster.BlastPlaster;
import mctmods.blastplaster.worldhealer.WorldHealerSaveDataSupplier;

import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public class WorldTickEventHandler {

  public WorldTickEventHandler() {
    MinecraftForge.EVENT_BUS.register(this);
  }

  @SubscribeEvent
  public void onWorldTick(TickEvent.LevelTickEvent event) {
    if (event.level.isClientSide) return;
    WorldHealerSaveDataSupplier worldHealer = BlastPlaster.getWorldHealer((ServerLevel) event.level);
    if (worldHealer != null) {
      worldHealer.onTick();
    }
  }
}
