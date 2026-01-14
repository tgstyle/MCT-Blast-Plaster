package mctmods.blastplaster;

import com.lothrazar.library.config.ConfigTemplate;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.ForgeConfigSpec.BooleanValue;
import net.minecraftforge.common.ForgeConfigSpec.IntValue;
import net.minecraftforge.common.ForgeConfigSpec.ConfigValue;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Arrays;

public class Config extends ConfigTemplate {

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
  private static final Map<TagKey<Block>, Block> TREE_MAP = new HashMap<>();
  private static final Map<Block, TagKey<Block>> LEAF_TO_LOG_MAP = new HashMap<>();

  static {
    final ForgeConfigSpec.Builder builder = builder();
    builder.push(BlastPlaster.MODID);
    MIN_TICKS_BEFORE_HEAL = builder.comment("Minimum ticks before healing starts after an explosion.")
            .defineInRange("TickStartDelay", 300, 1, 600000);
    RANDOM_TICK_VAR = builder.comment("Random tick variation added to the delay for each healing layer.")
            .defineInRange("TickRandomInterval", 60, 1, 600000);
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
    TREE_LOG_LEAF_PAIRS = builder.comment("List of tree log tag to leaf block pairs for vanilla tree healing (format: modid:log_tag=modid:leaf_block).")
            .defineListAllowEmpty("TreeLogLeafPairs", Arrays.asList(
                    "minecraft:oak_logs=minecraft:oak_leaves",
                    "minecraft:spruce_logs=minecraft:spruce_leaves",
                    "minecraft:birch_logs=minecraft:birch_leaves",
                    "minecraft:jungle_logs=minecraft:jungle_leaves",
                    "minecraft:acacia_logs=minecraft:acacia_leaves",
                    "minecraft:dark_oak_logs=minecraft:dark_oak_leaves",
                    "minecraft:mangrove_logs=minecraft:mangrove_leaves",
                    "minecraft:cherry_logs=minecraft:cherry_leaves"
            ), s -> s instanceof String);
    MAX_TREE_SIZE = builder.comment("Maximum number of blocks in a tree to allow full tree healing (prevents performance issues with very large trees).")
            .defineInRange("MaxTreeSize", 500, 0, 10000);
    builder.pop();
    SPEC = builder.build();
  }

  public Config() {
    SPEC.setConfig(setup(BlastPlaster.MODID + "-common"));
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

  private static void buildMaps() {
    for (String pair : TREE_LOG_LEAF_PAIRS.get()) {
      String[] parts = pair.split("=");
      if (parts.length == 2) {
        ResourceLocation logLoc = ResourceLocation.tryParse(parts[0]);
        ResourceLocation leafLoc = ResourceLocation.tryParse(parts[1]);
        if (logLoc != null && leafLoc != null) {
          TagKey<Block> logTag = TagKey.create(Registries.BLOCK, logLoc);
          Block leafBlock = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(leafLoc);
          if (leafBlock != null) {
            TREE_MAP.put(logTag, leafBlock);
            LEAF_TO_LOG_MAP.put(leafBlock, logTag);
          }
        }
      }
    }
  }
}
