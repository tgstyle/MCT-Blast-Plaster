package mctmods.blastplaster;

import mctmods.blastplaster.blocks.BlockLight;
import mctmods.blastplaster.handler.ExplosionEventHandler;
import mctmods.blastplaster.handler.WorldEventHandler;
import mctmods.blastplaster.handler.WorldTickEventHandler;
import mctmods.blastplaster.network.NetworkHandler;
import mctmods.blastplaster.proxies.CommonProxy;
import mctmods.blastplaster.worldhealer.WorldHealerSaveDataSupplier;

import net.minecraft.block.Block;
import net.minecraft.world.World;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.io.File;

@Mod(
		modid = BlastPlaster.MODID,
		name = BlastPlaster.MODNAME,
		useMetadata = true,
		acceptedMinecraftVersions = "[1.12.2,1.13)",
		acceptableRemoteVersions = "*",
		dependencies =
				"required-after:forge@[14.23.+,);" +
						"after:dynamictrees;")

@EventBusSubscriber
public class BlastPlaster {

	public static final String MODID = "blastplaster";
	public static final String MODNAME = "MCT Blast Plaster";
	public static final BlockLight LIGHT = new BlockLight();

	@SidedProxy(clientSide = "mctmods.blastplaster.proxies.ClientProxy", serverSide = "mctmods.blastplaster.proxies.CommonProxy")
	public static CommonProxy proxy;
	public static Logger logger = LogManager.getLogger(MODID);
	public static Configuration config;
	public static boolean dynamictrees;
	private static WorldEventHandler worldEventHandler;

	public static void debug(String message, Object... args) { if (Config.debugLogging()) { logger.info(message, args); }}

	public static WorldHealerSaveDataSupplier getWorldHealer(World world) { return worldEventHandler == null ? null : worldEventHandler.getWorldHealers().get(world); }

	@SubscribeEvent public static void registerBlocks(RegistryEvent.Register<Block> event) { event.getRegistry().register(LIGHT); }

	@EventBusSubscriber(Side.CLIENT)
	public static class ConfigReloadHandler {

		@SubscribeEvent public static void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
			if (MODID.equals(event.getModID())) { Config.syncConfig(); }
		}
	}

	@EventHandler public void preInit(FMLPreInitializationEvent event) {
		config = new Configuration(new File(event.getModConfigurationDirectory(), "mct_blastplaster.cfg"));
		dynamictrees = Loader.isModLoaded("dynamictrees");

		Config.syncConfig();
		NetworkHandler.init();
		worldEventHandler = new WorldEventHandler();
	}

	@EventHandler public void init(FMLInitializationEvent event) {
		new WorldTickEventHandler();
		new ExplosionEventHandler();
	}
}
