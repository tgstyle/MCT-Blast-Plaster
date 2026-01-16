package mctmods.blastplaster;

import mctmods.blastplaster.handler.ExplosionEventHandler;
import mctmods.blastplaster.handler.WorldEventHandler;
import mctmods.blastplaster.handler.WorldTickEventHandler;
import mctmods.blastplaster.worldhealer.WorldHealerSaveDataSupplier;

import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.ModList;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(BlastPlaster.MODID)
public class BlastPlaster {

  public static final String MODID = "blastplaster";
  public static final Logger LOGGER = LogManager.getLogger();
  private static WorldEventHandler WEV;

  public BlastPlaster(FMLJavaModLoadingContext context) {
    Config.load();
    context.getModEventBus().addListener(this::setup);
    BlastPlaster.WEV = new WorldEventHandler();
  }

  private void setup(final FMLCommonSetupEvent event) {
    new WorldTickEventHandler();
    new ExplosionEventHandler();
    if (ModList.get().isLoaded("dynamictrees") && Config.healFullTrees()) {
      LOGGER.info("Dynamic Trees detected and full tree healing enabled. Using DT integration.");
    }
    if (ModList.get().isLoaded("alexscaves")) {
      LOGGER.info("Alex's Caves detected. Registering nuclear explosion compatibility.");
      MinecraftForge.EVENT_BUS.register(new AlexsCavesCompat());
    }
  }

  public static WorldHealerSaveDataSupplier getWorldHealer(ServerLevel level) {
    return WEV.getWorldHealers().get(level);
  }
}
