package mctmods.blastplaster;

import mctmods.blastplaster.handler.ExplosionEventHandler;
import mctmods.blastplaster.handler.WorldEventHandler;
import mctmods.blastplaster.handler.WorldTickEventHandler;
import mctmods.blastplaster.util.BlastPlasterUtil;
import mctmods.blastplaster.util.compat.AlexsCavesCompat;
import mctmods.blastplaster.worldhealer.WorldHealerSaveDataSupplier;

import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
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

  public static void debug(String message, Object... args) { if (Config.debugLogging()) { LOGGER.info(message, args); } }

  private void setup(final FMLCommonSetupEvent event) {
    new WorldTickEventHandler();
    new ExplosionEventHandler();
    if (BlastPlasterUtil.DT_LOADED && Config.healFullTrees()) { LOGGER.info("Dynamic Trees detected and full tree healing enabled. Using DT integration."); }
    if (BlastPlasterUtil.AC_LOADED) {
      LOGGER.info("Alex's Caves detected. Registering nuclear explosion compatibility.");
      MinecraftForge.EVENT_BUS.register(new AlexsCavesCompat());
    }
  }

  public static WorldHealerSaveDataSupplier getWorldHealer(ServerLevel level) { return WEV.getWorldHealers().get(level); }
}
