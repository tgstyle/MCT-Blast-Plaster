package mctmods.blastplaster.handler;

import mctmods.blastplaster.BlastPlaster;
import mctmods.blastplaster.worldhealer.WorldHealerSaveDataSupplier;
import com.lothrazar.library.events.EventFlib;

import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public class WorldTickEventHandler extends EventFlib {

  @SubscribeEvent
  public void onWorldTick(TickEvent.LevelTickEvent event) {
    if (event.level.isClientSide) return;
    WorldHealerSaveDataSupplier worldHealer = BlastPlaster.getWorldHealer((ServerLevel) event.level);
    if (worldHealer != null) {
      worldHealer.onTick();
    }
  }
}
