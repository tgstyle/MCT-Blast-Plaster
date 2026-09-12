package mctmods.blastplaster.util;

import mctmods.blastplaster.BlastPlaster;
import mctmods.blastplaster.Config;
import mctmods.blastplaster.helper.BlockStatePosWrapper;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

public final class BlockConversions {

  private BlockConversions() {}

  private static final Map<List<String>, RuleSet> PARSED = new ConcurrentHashMap<>();
  private static final RuleSet NONE = new RuleSet(List.of(), Set.of());

  private record Rule(Predicate<BlockState> source, Block result, float chance) {}

  private record RuleSet(List<Rule> rules, Set<Block> results) {
    boolean isResult(BlockState state) { return results.contains(state.getBlock()); }
  }

  public static void applyAll(ServerLevel level, List<BlockStatePosWrapper> wrappers) {
    if (wrappers.isEmpty()) { return; }
    RuleSet ruleSet = ruleSet(Config.getBlockConversions());
    if (ruleSet.rules().isEmpty()) { return; }

    int converted = 0;
    int removed = 0;
    for (BlockStatePosWrapper wrapper : wrappers) {
      BlockState next = convert(level, ruleSet, wrapper.getState());
      if (next == null) { continue; }
      wrapper.convertTo(next);
      if (next.isAir()) { removed++; }
      else { converted++; }
    }

    if (converted > 0 || removed > 0) { BlastPlaster.debug("Conversions: {} of {} captured block(s) converted, {} left empty", converted, wrappers.size(), removed); }
  }

  @Nullable private static BlockState convert(ServerLevel level, RuleSet ruleSet, BlockState state) {
    if (state.isAir() || ruleSet.isResult(state)) { return null; }
    for (Rule rule : ruleSet.rules()) {
      if (!rule.source().test(state)) { continue; }
      if (rule.chance() < 1.0f && level.getRandom().nextFloat() >= rule.chance()) { return null; }
      if (rule.result() == Blocks.AIR) { return Blocks.AIR.defaultBlockState(); }
      return rule.result().withPropertiesOf(state);
    }
    return null;
  }

  private static RuleSet ruleSet(List<String> lines) {
    if (lines.isEmpty()) { return NONE; }
    return PARSED.computeIfAbsent(List.copyOf(lines), BlockConversions::parse);
  }

  private static RuleSet parse(List<String> lines) {
    List<Rule> rules = new ArrayList<>();
    Set<Block> results = new HashSet<>();
    for (String line : lines) {
      Rule rule = parseRule(line);
      if (rule == null) { continue; }
      rules.add(rule);
      if (rule.result() != Blocks.AIR) { results.add(rule.result()); }
    }
    if (rules.isEmpty()) { return NONE; }
    BlastPlaster.LOGGER.info("[BlastPlaster] Block conversions: {} rule(s) in force", rules.size());
    return new RuleSet(List.copyOf(rules), Set.copyOf(results));
  }

  @Nullable private static Rule parseRule(String line) {
    String text = line.trim();
    if (text.isEmpty() || text.startsWith("//")) { return null; }

    float chance = 1.0f;
    int at = text.lastIndexOf('@');
    if (at >= 0) {
      try { chance = Float.parseFloat(text.substring(at + 1).trim()); }
      catch (NumberFormatException e) {
        BlastPlaster.LOGGER.warn("[BlastPlaster] Block conversions: '{}' has an unreadable chance, rule ignored", line);
        return null;
      }
      text = text.substring(0, at).trim();
    }
    if (chance <= 0.0f) { return null; }
    if (chance > 1.0f) { chance = 1.0f; }

    int split = text.indexOf('=');
    if (split < 1 || split == text.length() - 1) {
      BlastPlaster.LOGGER.warn("[BlastPlaster] Block conversions: '{}' is not <source>=<result>[@chance], rule ignored", line);
      return null;
    }

    Block result = resultBlock(text.substring(split + 1).trim(), line);
    if (result == null) { return null; }

    String source = text.substring(0, split).trim();
    if (source.startsWith("#")) {
      Identifier tagLoc = Identifier.tryParse(source.substring(1));
      if (tagLoc == null) {
        BlastPlaster.LOGGER.warn("[BlastPlaster] Block conversions: '{}' is not a block tag, rule ignored", source);
        return null;
      }
      TagKey<Block> tag = TagKey.create(Registries.BLOCK, tagLoc);
      return new Rule(state -> state.is(tag), result, chance);
    }

    Block block = lookup(source);
    if (block == null) {
      BlastPlaster.LOGGER.warn("[BlastPlaster] Block conversions: '{}' is not a known block, rule ignored", source);
      return null;
    }
    return new Rule(state -> state.is(block), result, chance);
  }

  @Nullable private static Block resultBlock(String text, String line) {
    if (text.equalsIgnoreCase("nothing") || text.equalsIgnoreCase("air") || text.equalsIgnoreCase("minecraft:air")) { return Blocks.AIR; }

    Block block = lookup(text);
    if (block == null) {
      BlastPlaster.LOGGER.warn("[BlastPlaster] Block conversions: '{}' names no known result block, rule ignored", line);
      return null;
    }
    return block;
  }

  @Nullable private static Block lookup(String id) {
    Identifier loc = Identifier.tryParse(id);
    if (loc == null) { return null; }
    Block block = BuiltInRegistries.BLOCK.getOptional(loc).orElse(null);
    return block == Blocks.AIR ? null : block;
  }
}
