package mctmods.blastplaster;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.io.WritingMode;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.ForgeConfigSpec.BooleanValue;
import net.minecraftforge.common.ForgeConfigSpec.ConfigValue;
import net.minecraftforge.common.ForgeConfigSpec.EnumValue;
import net.minecraftforge.common.ForgeConfigSpec.IntValue;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class Config {

  public enum ExplosionMode {
    HEAL,
    EJECT_DROPS,
    VISUAL_TOSS
  }

  private static final ForgeConfigSpec SPEC;

  private static final EnumValue<ExplosionMode> EXPLOSION_MODE;
  private static final BooleanValue ENABLE_FAKE_TOSSED_BLOCKS;
  private static final BooleanValue ENABLE_EXPLOSION_FLASH;
  private static final IntValue EXPLOSION_FLASH_DURATION;
  private static final IntValue EXPLOSION_FLASH_LIGHT_LEVEL;
  private static final IntValue EXPLOSION_FLASH_PARTICLE_COUNT;
  private static final IntValue EXPLOSION_FLASH_PULSES;
  private static final BooleanValue ENABLE_EXPLOSION_SMOKE;
  private static final IntValue EXPLOSION_SMOKE_DURATION;
  private static final IntValue EXPLOSION_SMOKE_PARTICLE_COUNT;
  private static final BooleanValue PLAYER_TNT_ALWAYS_DROPS;
  private static final BooleanValue PLAYER_TNT_DROP_FULL_BLOCKS;

  private static final IntValue MIN_TICKS_BEFORE_HEAL;
  private static final IntValue RANDOM_TICK_VAR;
  private static final BooleanValue OVERRIDE_BLOCKS;
  private static final BooleanValue HEAL_FULL_TREES;

  private static final BooleanValue HEAL_CREEPERS;
  private static final BooleanValue HEAL_NONPLAYER_TNT;
  private static final BooleanValue HEAL_WITHER;
  private static final BooleanValue HEAL_ALL;
  private static final BooleanValue PROCESS_PLAYER_IGNITED_TNT;
  private static final ConfigValue<List<? extends String>> CUSTOM_ENTITIES_TO_HEAL;

  private static final BooleanValue ENABLE_ALEXSCAVES_NUKES;

  private static final BooleanValue DT_SPECIAL_DROPS;
  private static final ConfigValue<List<? extends String>> DT_LOG_MAPPINGS;

  private static final ConfigValue<List<? extends String>> TREE_LOG_LEAF_PAIRS;
  private static final IntValue MAX_TREE_SIZE;

  private static final BooleanValue ENABLE_DROP_SCAVENGER;

  private static final Map<TagKey<Block>, Block> TREE_MAP = new HashMap<>();
  private static final Map<String, Block> DT_LOG_MAP = new HashMap<>();

  private static final List<String> DT_SUFFIXES = Arrays.asList("_branch", "_leaves", "_root", "_surface_root", "_fancy_branch", "_cactus", "_bark", "_fruited");

  static {
    final ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
    builder.push(BlastPlaster.MODID);

    builder.comment(
            "",
            "================================================================",
            "  EXPLOSION MODE & VISUALS",
            "================================================================");
    builder.push("explosion");
    EXPLOSION_MODE = builder.comment("HEAL (default): blocks disappear then slowly restore + fake tossed blocks fly out",
                    "EJECT_DROPS: blocks gone forever, real drops with 1/3 chance per block (exactly matching vanilla creeper), otherwise fake tossed block instead (fakes exactly make up the remaining ~2/3, 100% visual coverage)",
                    "VISUAL_TOSS: blocks gone forever, only fake tossed blocks fly out (no real drops, no restore)")
            .defineEnum("ExplosionMode", ExplosionMode.HEAL);

    ENABLE_FAKE_TOSSED_BLOCKS = builder.comment("Spawn visible 3D fake block entities that fly out and despawn after ~3 seconds (no pickup, no placement). In EJECT_DROPS mode these exactly fill the blocks that do NOT drop real items. Ignored in modes where not applicable.")
            .define("EnableFakeTossedBlocks", true);

    ENABLE_EXPLOSION_FLASH = builder.comment("Enable temporary light source flash at explosion center (LightBlock + many bright FLASH particles) for realism - visible even in full daylight.")
            .define("EnableExplosionFlash", true);
    EXPLOSION_FLASH_DURATION = builder.comment("Server ticks the flash LightBlock remains visible (placed AFTER blocks are cleared to AIR; 4-12 recommended).")
            .defineInRange("ExplosionFlashDuration", 28, 1, 40);
    EXPLOSION_FLASH_LIGHT_LEVEL = builder.comment("Light level of the flash (0-15).")
            .defineInRange("ExplosionFlashLightLevel", 15, 0, 15);
    EXPLOSION_FLASH_PARTICLE_COUNT = builder.comment("Number of bright FLASH particles for explosion visual flash (higher values = stronger camera-flash pop that overpowers ambient light on surrounding blocks).")
            .defineInRange("ExplosionFlashParticleCount", 240, 20, 600);
    EXPLOSION_FLASH_PULSES = builder.comment("Number of staggered FLASH particle bursts (1 = single burst, 4 = dramatic camera-flash strobe/overexposure that cuts through torches/ambient light).")
            .defineInRange("ExplosionFlashPulses", 4, 1, 6);

    ENABLE_EXPLOSION_SMOKE = builder.comment("Enable large poof of campfire smoke rising from explosion center (lingering realistic smoke cloud visible in ALL modes, even daylight).")
            .define("EnableExplosionSmoke", true);
    EXPLOSION_SMOKE_DURATION = builder.comment("Total server ticks the smoke poof effect continues (200 ticks = 10 seconds; bursts every 15 ticks).")
            .defineInRange("ExplosionSmokeDuration", 200, 40, 1200);
    EXPLOSION_SMOKE_PARTICLE_COUNT = builder.comment("CAMPFIRE_SIGNAL_SMOKE particles per burst (first burst doubled for initial outward poof; total ~15 particles over 10s at default; higher = thicker rising column).")
            .defineInRange("ExplosionSmokeParticleCount", 1, 1, 10);

    PLAYER_TNT_ALWAYS_DROPS = builder.comment("If true (default), player-ignited TNT explosions ALWAYS use EJECT_DROPS behavior (real item drops + permanent removal) regardless of global ExplosionMode. Set false to respect the selected global mode. NOTE: Requires ProcessPlayerIgnitedTNT=true to have any effect.")
            .define("PlayerTNTAlwaysDrops", true);
    PLAYER_TNT_DROP_FULL_BLOCKS = builder.comment("If true, player-ignited TNT drops the full block item (silk-touch like). Only applies when PlayerTNTAlwaysDrops=true and effective mode is EJECT_DROPS. DT blocks prioritize DT_SPECIAL_DROPS if enabled.")
            .define("PlayerTNTDropFullBlocks", false);
    builder.pop();

    builder.comment(
            "",
            "================================================================",
            "  HEALING SETTINGS (HEAL mode only)",
            "================================================================");
    builder.push("healing");
    MIN_TICKS_BEFORE_HEAL = builder.comment("Minimum ticks before healing begins after explosion. Only used in HEAL mode.")
            .defineInRange("TickStartDelay", 600, 1, 600000);
    RANDOM_TICK_VAR = builder.comment("Random extra ticks added per healing layer. Only used in HEAL mode.")
            .defineInRange("TickRandomInterval", 200, 1, 600000);
    OVERRIDE_BLOCKS = builder.comment("Replace any block (including fluids) when healing.")
            .define("OverrideBlocks", true);
    HEAL_FULL_TREES = builder.comment("When a tree is partially exploded, heal the entire tree. Enables tree expansion logic.")
            .define("HealFullTrees", true);
    builder.pop();

    builder.comment(
            "",
            "================================================================",
            "  WHICH EXPLOSIONS TO PROCESS",
            "================================================================");
    builder.push("explosion_sources");
    HEAL_CREEPERS = builder.comment("Process creeper explosions according to selected mode.")
            .define("HealCreepers", true);
    HEAL_NONPLAYER_TNT = builder.comment("Process TNT explosions not ignited by players according to selected mode.")
            .define("HealNonPlayerTNT", true);
    HEAL_WITHER = builder.comment("Process wither explosions according to selected mode.")
            .define("HealWither", true);
    HEAL_ALL = builder.comment("Process EVERY explosion regardless of source (overrides all specific options above).")
            .define("HealAll", false);
    PROCESS_PLAYER_IGNITED_TNT = builder.comment("If true (default), player-ignited TNT explosions are processed (subject to PlayerTNTAlwaysDrops). Set false to completely ignore player TNT like vanilla.")
            .define("ProcessPlayerIgnitedTNT", true);
    CUSTOM_ENTITIES_TO_HEAL = builder.comment("Extra entity IDs (modid:entity) whose explosions should be processed.")
            .defineListAllowEmpty("CustomEntitiesToHeal", List.of("undeadnights:demolition_zombie"), s -> s instanceof String);
    builder.pop();

    builder.comment(
            "",
            "================================================================",
            "  ALEX'S CAVES COMPAT",
            "================================================================");
    builder.push("compatibility");
    ENABLE_ALEXSCAVES_NUKES = builder.comment("If true and Alex's Caves is loaded, nucleeper explosions are processed according to selected mode (treated as non-player TNT).")
            .define("EnableAlexsCavesNukes", true);
    builder.pop();

    builder.comment(
            "",
            "================================================================",
            "  EJECT_DROPS SETTINGS",
            "================================================================");
    builder.push("eject_drops");
    DT_SPECIAL_DROPS = builder.comment("For Dynamic Trees blocks in EJECT_DROPS mode: drop simple logs + sticks instead of full DT items. Applies to all EJECT_DROPS including player TNT (overrides full blocks intent for DT).")
            .define("DynamicTreesSpecialDrops", true);
    DT_LOG_MAPPINGS = builder.comment("Mappings for Dynamic Trees blocks → vanilla log dropped in EJECT_DROPS mode.",
                    "Format: dynamictrees:tree_type=modid:log_block",
                    "Add your own for modded DT trees. Unknown trees fall back to oak_log.")
            .defineListAllowEmpty("DynamicTreesLogMappings",
                    List.of(
                            "dynamictrees:oak=oak_log",
                            "dynamictrees:spruce=spruce_log",
                            "dynamictrees:birch=birch_log",
                            "dynamictrees:jungle=jungle_log",
                            "dynamictrees:acacia=acacia_log",
                            "dynamictrees:dark_oak=dark_oak_log",
                            "dynamictrees:mangrove=mangrove_log",
                            "dynamictrees:cherry=cherry_log",
                            "dynamictrees:bamboo=bamboo",
                            "dynamictrees:azalea=oak_log"
                    ),
                    s -> s instanceof String);
    ENABLE_DROP_SCAVENGER = builder.comment("FINAL SAFETY NET: Delete any stray ItemEntities spawned by vanilla/modded drop logic in non-EJECT modes (covers seeds, vines, etc. that slip through). Default true. Set false only if you have performance concerns with many explosions.")
            .define("EnableDropScavenger", true);
    builder.pop();

    builder.comment(
            "",
            "================================================================",
            "  TREE & MISC HELPERS",
            "================================================================");
    builder.push("trees");
    TREE_LOG_LEAF_PAIRS = builder.comment("Custom tree log<->leaf pairings for full tree healing.",
                    "Format: modid:log_tag=modid:leaf_block (only needed for unusual trees)")
            .defineListAllowEmpty("TreeLogLeafPairs", List.of(), s -> s instanceof String);
    MAX_TREE_SIZE = builder.comment("Max blocks allowed in a tree for full healing (prevents lag). Large old-growth jungle trees often exceed 7500 blocks; Alex's Caves nukes hit massive areas - set higher if you see warnings.")
            .defineInRange("MaxTreeSize", 20000, 0, 50000);
    builder.pop();

    SPEC = builder.build();
  }

  private Config() {}

  public static void load() {
    CommentedFileConfig configData = CommentedFileConfig.builder(FMLPaths.CONFIGDIR.get().resolve(BlastPlaster.MODID + "-common.toml")).sync().autosave().writingMode(WritingMode.REPLACE).build();
    configData.load();
    SPEC.setConfig(configData);
    buildDTLogMap();
    validateConfig();
  }

  private static void validateConfig() {
    if (playerTNTAlwaysDrops() && !processPlayerIgnitedTNT()) { BlastPlaster.LOGGER.warn("[BlastPlaster] Config: PlayerTNTAlwaysDrops=true but ProcessPlayerIgnitedTNT=false - player TNT explosions will be ignored."); }
    if (!isAlexsCavesNukesEnabled() && ModList.get().isLoaded("alexscaves")) { BlastPlaster.LOGGER.info("[BlastPlaster] Alex's Caves loaded but EnableAlexsCavesNukes=false (nukes will be ignored)."); }
  }

  public static ExplosionMode getExplosionMode() { return EXPLOSION_MODE.get(); }
  public static boolean enableFakeTossedBlocks() { return ENABLE_FAKE_TOSSED_BLOCKS.get(); }
  public static boolean enableExplosionFlash() { return ENABLE_EXPLOSION_FLASH.get(); }
  public static int getExplosionFlashDuration() { return EXPLOSION_FLASH_DURATION.get(); }
  public static int getExplosionFlashLightLevel() { return EXPLOSION_FLASH_LIGHT_LEVEL.get(); }
  public static int getExplosionFlashParticleCount() { return EXPLOSION_FLASH_PARTICLE_COUNT.get(); }
  public static int getExplosionFlashPulses() { return EXPLOSION_FLASH_PULSES.get(); }
  public static boolean enableExplosionSmoke() { return ENABLE_EXPLOSION_SMOKE.get(); }
  public static int getExplosionSmokeDuration() { return EXPLOSION_SMOKE_DURATION.get(); }
  public static int getExplosionSmokeParticleCount() { return EXPLOSION_SMOKE_PARTICLE_COUNT.get(); }
  public static boolean playerTNTAlwaysDrops() { return PLAYER_TNT_ALWAYS_DROPS.get(); }
  public static boolean playerTNTDropFullBlocks() { return PLAYER_TNT_DROP_FULL_BLOCKS.get(); }
  public static boolean healCreepers() { return HEAL_CREEPERS.get(); }
  public static boolean healNonPlayerTNT() { return HEAL_NONPLAYER_TNT.get(); }
  public static boolean healWither() { return HEAL_WITHER.get(); }
  public static boolean healAll() { return HEAL_ALL.get(); }
  public static boolean processPlayerIgnitedTNT() { return PROCESS_PLAYER_IGNITED_TNT.get(); }

  @SuppressWarnings("unchecked")
  public static List<String> getCustomEntitiesToHeal() { return (List<String>) CUSTOM_ENTITIES_TO_HEAL.get(); }

  public static boolean isAlexsCavesNukesEnabled() { return ENABLE_ALEXSCAVES_NUKES.get() && ModList.get().isLoaded("alexscaves"); }
  public static int getMinimumTicksBeforeHeal() { return MIN_TICKS_BEFORE_HEAL.get(); }
  public static int getRandomTickVar() { return RANDOM_TICK_VAR.get(); }
  public static boolean isOverride() { return OVERRIDE_BLOCKS.get(); }
  public static boolean healFullTrees() { return HEAL_FULL_TREES.get(); }
  public static boolean dtSpecialDrops() { return DT_SPECIAL_DROPS.get(); }
  public static int getMaxTreeSize() { return MAX_TREE_SIZE.get(); }
  public static boolean enableDropScavenger() { return ENABLE_DROP_SCAVENGER.get(); }

  public static Map<TagKey<Block>, Block> getTreeMap() {
    if (TREE_MAP.isEmpty()) { buildMaps(); }
    return TREE_MAP;
  }

  private static void buildMaps() {
    TREE_MAP.clear();

    for (Block leafBlock : ForgeRegistries.BLOCKS.getValues()) {
      ResourceLocation loc = ForgeRegistries.BLOCKS.getKey(leafBlock);
      if (loc != null && loc.getPath().endsWith("_leaves")) {
        String path = loc.getPath();
        String prefix = path.substring(0, path.length() - "_leaves".length());
        String logPath = prefix + "_logs";
        ResourceLocation logLoc = ResourceLocation.fromNamespaceAndPath(loc.getNamespace(), logPath);
        TagKey<Block> logTag = TagKey.create(Registries.BLOCK, logLoc);

        if (!Objects.requireNonNull(ForgeRegistries.BLOCKS.tags()).getTag(logTag).isEmpty()) { TREE_MAP.put(logTag, leafBlock); }
      }
    }

    for (String pair : TREE_LOG_LEAF_PAIRS.get()) {
      String[] parts = pair.split("=");
      if (parts.length == 2) {
        ResourceLocation logLoc = ResourceLocation.tryParse(parts[0].trim());
        ResourceLocation leafLoc = ResourceLocation.tryParse(parts[1].trim());
        if (logLoc != null && leafLoc != null) {
          TagKey<Block> logTag = TagKey.create(Registries.BLOCK, logLoc);
          Block leafBlock = ForgeRegistries.BLOCKS.getValue(leafLoc);
          if (leafBlock != null && !Objects.requireNonNull(ForgeRegistries.BLOCKS.tags()).getTag(logTag).isEmpty()) {
            TREE_MAP.put(logTag, leafBlock);
          }
        }
      }
    }
  }

  private static void buildDTLogMap() {
    DT_LOG_MAP.clear();
    for (String entry : DT_LOG_MAPPINGS.get()) {
      String[] parts = entry.split("=");
      if (parts.length == 2) {
        String dtKey = parts[0].trim().toLowerCase();
        ResourceLocation logLoc = ResourceLocation.tryParse(parts[1].trim());
        if (logLoc != null) {
          Block logBlock = ForgeRegistries.BLOCKS.getValue(logLoc);
          if (logBlock != null) { DT_LOG_MAP.put(dtKey, logBlock); }
        }
      }
    }
  }

  public static Block getDTLogForPath(String path) {
    if (path == null) { return Blocks.OAK_LOG; }

    String key = path.toLowerCase().trim();

    Block direct = DT_LOG_MAP.get(key);
    if (direct != null) { return direct; }

    if (!key.startsWith("dynamictrees:")) { return Blocks.OAK_LOG; }

    String base = key.substring(13);

    for (String suffix : DT_SUFFIXES) {
      if (base.endsWith(suffix)) {
        String species = base.substring(0, base.length() - suffix.length());
        String speciesKey = "dynamictrees:" + species;
        Block mapped = DT_LOG_MAP.get(speciesKey);
        if (mapped != null) { return mapped; }
      }
    }

    String[] segments = base.split("_");
    if (segments.length > 1) {
      String[] speciesSegments = Arrays.copyOf(segments, segments.length - 1);
      String species = String.join("_", speciesSegments);
      String lookupKey = "dynamictrees:" + species;
      Block mapped = DT_LOG_MAP.get(lookupKey);
      if (mapped != null) { return mapped; }
    }

    return Blocks.OAK_LOG;
  }
}
