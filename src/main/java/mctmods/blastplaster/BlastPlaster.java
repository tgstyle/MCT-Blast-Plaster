package mctmods.blastplaster;

import mctmods.blastplaster.handler.ExplosionEventHandler;
import mctmods.blastplaster.handler.WorldEventHandler;
import mctmods.blastplaster.handler.WorldTickEventHandler;
import mctmods.blastplaster.util.compat.AlexsCavesCompat;
import mctmods.blastplaster.worldhealer.WorldHealerSaveDataSupplier;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.fml.ModContainer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(BlastPlaster.MODID)
public class BlastPlaster {

  public static final String MODID = "blastplaster";
  public static final Logger LOGGER = LogManager.getLogger();
  private static WorldEventHandler WEV;

  public BlastPlaster(IEventBus modEventBus, ModContainer modContainer) {
    modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.COMMON, Config.SPEC);

    modEventBus.addListener(this::setup);
    BlastPlaster.WEV = new WorldEventHandler();
  }

  private void setup(final FMLCommonSetupEvent event) {
    Config.load();

    new WorldTickEventHandler();
    new ExplosionEventHandler();

    if (ModList.get().isLoaded("dynamictrees") && Config.healFullTrees()) {
      LOGGER.info("Dynamic Trees detected and full tree healing enabled. Using DT integration.");
    }

    if (ModList.get().isLoaded("alexscaves")) {
      LOGGER.info("Alex's Caves detected. Registering nuclear explosion compatibility.");
      NeoForge.EVENT_BUS.register(new AlexsCavesCompat());
    }
  }

  public static WorldHealerSaveDataSupplier getWorldHealer(ServerLevel level) {
    return WEV.getWorldHealers().get(level);
  }
}
