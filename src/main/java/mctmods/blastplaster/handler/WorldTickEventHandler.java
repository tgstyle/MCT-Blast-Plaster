package mctmods.blastplaster.handler;

import mctmods.blastplaster.BlastPlaster;
import mctmods.blastplaster.worldhealer.WorldHealerSaveDataSupplier;

import net.minecraft.server.level.ServerLevel;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

public class WorldTickEventHandler {

  public WorldTickEventHandler() {
    NeoForge.EVENT_BUS.register(this);
  }

  @SubscribeEvent public void onWorldTick(LevelTickEvent.Pre event) {
    if (event.getLevel().isClientSide) { return; }
    WorldHealerSaveDataSupplier worldHealer = BlastPlaster.getWorldHealer((ServerLevel) event.getLevel());
    if (worldHealer != null) {
      worldHealer.onTick();
    }
  }
}
