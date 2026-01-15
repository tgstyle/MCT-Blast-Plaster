package mctmods.blastplaster;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.io.WritingMode;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.ForgeConfigSpec.BooleanValue;
import net.minecraftforge.common.ForgeConfigSpec.IntValue;
import net.minecraftforge.common.ForgeConfigSpec.ConfigValue;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;

import net.minecraft.core.HolderSet;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;

public class Config {

  private static final ForgeConfigSpec SPEC;
  private static final IntValue MIN_TICKS_BEFORE_HEAL;
  private static final IntValue RANDOM_TICK_VAR;
  private static final BooleanValue OVERRIDE_BLOCKS;
  private static final BooleanValue DROP_IF_ALREADY_BLOCK;
  private static final BooleanValue HEAL_CREEPERS;
  private static final BooleanValue HEAL_NONPLAYER_TNT;
  private static final BooleanValue HEAL_WITHER;
  private static final BooleanValue HEAL_ALL;
  private static final BooleanValue HEAL_FULL_TREES;
  private static final ConfigValue<List<? extends String>> TREE_LOG_LEAF_PAIRS;
  private static final IntValue MAX_TREE_SIZE;
  private static final ConfigValue<List<? extends String>> CUSTOM_ENTITIES_TO_HEAL;
  private static final Map<TagKey<Block>, Block> TREE_MAP = new HashMap<>();
  private static final Map<Block, TagKey<Block>> LEAF_TO_LOG_MAP = new HashMap<>();

  static {
    final ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
    builder.push(BlastPlaster.MODID);
    MIN_TICKS_BEFORE_HEAL = builder.comment("Minimum ticks before healing starts after an explosion.")
            .defineInRange("TickStartDelay", 600, 1, 600000);
    RANDOM_TICK_VAR = builder.comment("Random tick variation added to the delay for each healing layer.")
            .defineInRange("TickRandomInterval", 100, 1, 600000);
    OVERRIDE_BLOCKS = builder.comment("If true, override non-air blocks during healing (e.g., replace fluids or other blocks).")
            .define("OverrideBlocks", true);
    DROP_IF_ALREADY_BLOCK = builder.comment("If true and OverrideBlocks is false, drop the healed block as an item if the position is occupied.")
            .define("DropBlockConflict", true);
    HEAL_CREEPERS = builder.comment("If true, heal explosions caused by creepers.")
            .define("HealCreepers", true);
    HEAL_NONPLAYER_TNT = builder.comment("If true, heal TNT explosions not ignited by players (e.g., redstone or other entities).")
            .define("HealNonPlayerTNT", true);
    HEAL_WITHER = builder.comment("If true, heal explosions caused by withers.")
            .define("HealWither", true);
    HEAL_ALL = builder.comment("If true, heal all explosions regardless of source (overrides other heal options).")
            .define("HealAll", false);
    HEAL_FULL_TREES = builder.comment("If true, heal entire trees when part of a tree is damaged by an explosion.")
            .define("HealFullTrees", true);
    TREE_LOG_LEAF_PAIRS = builder.comment(
                    "Automatic detection handles trees that follow the standard naming convention ",
                    "(_logs tag and _leaves block in the same namespace).",
                    "Use this list only for custom pairings that do not follow the convention or to override auto-detected ones.",
                    "Format: modid:log_tag=modid:leaf_block")
            .defineListAllowEmpty("TreeLogLeafPairs", List.of(), s -> s instanceof String);
    MAX_TREE_SIZE = builder.comment("Maximum number of blocks in a tree to allow full tree healing (prevents performance issues with very large trees).")
            .defineInRange("MaxTreeSize", 500, 0, 10000);
    CUSTOM_ENTITIES_TO_HEAL = builder.comment("List of entity registry names (modid:entity_id) whose explosions should be healed under the healNonPlayerTNT category.")
            .defineListAllowEmpty("CustomEntitiesToHeal", List.of("undeadnights:demolition_zombie"), s -> s instanceof String);
    SPEC = builder.build();
  }

  private Config() {}

  public static void load() {
    CommentedFileConfig configData = CommentedFileConfig.builder(FMLPaths.CONFIGDIR.get().resolve(BlastPlaster.MODID + "-common.toml"))
            .sync().autosave().writingMode(WritingMode.REPLACE).build();
    configData.load();
    SPEC.setConfig(configData);
  }

  public static int getMinimumTicksBeforeHeal() {
    return MIN_TICKS_BEFORE_HEAL.get();
  }

  public static int getRandomTickVar() {
    return RANDOM_TICK_VAR.get();
  }

  public static boolean isOverride() {
    return OVERRIDE_BLOCKS.get();
  }

  public static boolean isDropIfAlreadyBlock() {
    return DROP_IF_ALREADY_BLOCK.get();
  }

  public static boolean healCreepers() {
    return HEAL_CREEPERS.get();
  }

  public static boolean healNonPlayerTNT() {
    return HEAL_NONPLAYER_TNT.get();
  }

  public static boolean healWither() {
    return HEAL_WITHER.get();
  }

  public static boolean healAll() {
    return HEAL_ALL.get();
  }

  public static boolean healFullTrees() {
    return HEAL_FULL_TREES.get();
  }

  public static int getMaxTreeSize() {
    return MAX_TREE_SIZE.get();
  }

  public static Map<TagKey<Block>, Block> getTreeMap() {
    if (TREE_MAP.isEmpty()) {
      buildMaps();
    }
    return TREE_MAP;
  }

  public static Map<Block, TagKey<Block>> getLeafToLogMap() {
    if (LEAF_TO_LOG_MAP.isEmpty()) {
      buildMaps();
    }
    return LEAF_TO_LOG_MAP;
  }

  @SuppressWarnings("unchecked")
  public static List<String> getCustomEntitiesToHeal() {
    return (List<String>) CUSTOM_ENTITIES_TO_HEAL.get();
  }

  private static void buildMaps() {
    TREE_MAP.clear();
    LEAF_TO_LOG_MAP.clear();

    for (Block leafBlock : ForgeRegistries.BLOCKS.getValues()) {
      ResourceLocation loc = ForgeRegistries.BLOCKS.getKey(leafBlock);
      if (loc != null && loc.getPath().endsWith("_leaves")) {
        String path = loc.getPath();
        String prefix = path.substring(0, path.length() - "_leaves".length());
        String logPath = prefix + "_logs";
        ResourceLocation logLoc = ResourceLocation.fromNamespaceAndPath(loc.getNamespace(), logPath);
        TagKey<Block> logTag = TagKey.create(Registries.BLOCK, logLoc);

        if (!Objects.requireNonNull(ForgeRegistries.BLOCKS.tags()).getTag(logTag).isEmpty()) {
          TREE_MAP.put(logTag, leafBlock);
          LEAF_TO_LOG_MAP.put(leafBlock, logTag);
        }
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
            LEAF_TO_LOG_MAP.put(leafBlock, logTag);
          }
        }
      }
    }
  }
}
