package mctmods.blastplaster.util;

import mctmods.blastplaster.BlastPlaster;
import mctmods.blastplaster.Config;
import mctmods.blastplaster.helper.BlockStatePosWrapper;

import net.minecraft.block.Block;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.WorldServer;
import net.minecraftforge.oredict.OreDictionary;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import javax.annotation.Nullable;

public final class BlockConversions {

    private BlockConversions() {}

    private static final Map<List<String>, RuleSet> PARSED = new ConcurrentHashMap<>();

    private static final class Rule {
        private final Predicate<IBlockState> source;
        private final Block result;
        private final int resultMeta;
        private final float chance;

        private Rule(Predicate<IBlockState> source, Block result, int resultMeta, float chance) {
            this.source = source;
            this.result = result;
            this.resultMeta = resultMeta;
            this.chance = chance;
        }
    }

    private static final class RuleSet {
        private final List<Rule> rules;
        private final Set<Block> results;

        private RuleSet(List<Rule> rules, Set<Block> results) {
            this.rules = rules;
            this.results = results;
        }

        private boolean isResult(IBlockState state) { return results.contains(state.getBlock()); }
    }

    private static final RuleSet NONE = new RuleSet(new ArrayList<>(), new HashSet<>());

    public static void applyAll(WorldServer world, List<BlockStatePosWrapper> wrappers) {
        if (wrappers.isEmpty()) { return; }
        RuleSet ruleSet = ruleSet(Config.view(world).getBlockConversions());
        if (ruleSet.rules.isEmpty()) { return; }

        int converted = 0;
        int removed = 0;
        for (BlockStatePosWrapper wrapper : wrappers) {
            IBlockState next = convert(world, ruleSet, wrapper.getState());
            if (next == null) { continue; }
            wrapper.convertTo(next);
            if (next.getBlock() == Blocks.AIR) { removed++; }
            else { converted++; }
        }

        if (converted > 0 || removed > 0) { BlastPlaster.debug("Conversions: {} of {} captured block(s) converted, {} left empty", converted, wrappers.size(), removed); }
    }

    @Nullable private static IBlockState convert(WorldServer world, RuleSet ruleSet, IBlockState state) {
        if (state.getBlock() == Blocks.AIR || ruleSet.isResult(state)) { return null; }
        for (Rule rule : ruleSet.rules) {
            if (!rule.source.test(state)) { continue; }
            if (rule.chance < 1.0f && world.rand.nextFloat() >= rule.chance) { return null; }
            if (rule.result == Blocks.AIR) { return Blocks.AIR.getDefaultState(); }
            if (rule.resultMeta >= 0) { return stateFromMeta(rule.result, rule.resultMeta); }
            return withPropertiesOf(rule.result.getDefaultState(), state);
        }
        return null;
    }

    private static RuleSet ruleSet(List<String> lines) {
        if (lines.isEmpty()) { return NONE; }
        return PARSED.computeIfAbsent(new ArrayList<>(lines), BlockConversions::parse);
    }

    private static RuleSet parse(List<String> lines) {
        List<Rule> rules = new ArrayList<>();
        Set<Block> results = new HashSet<>();
        for (String line : lines) {
            Rule rule = parseRule(line);
            if (rule == null) { continue; }
            rules.add(rule);
            if (rule.result != Blocks.AIR) { results.add(rule.result); }
        }
        if (rules.isEmpty()) { return NONE; }
        BlastPlaster.logger.info("[BlastPlaster] Block conversions: {} rule(s) in force", rules.size());
        return new RuleSet(rules, results);
    }

    @Nullable private static Rule parseRule(String line) {
        String text = line.trim();
        if (text.isEmpty() || text.startsWith("//")) { return null; }

        float chance = 1.0f;
        int at = text.lastIndexOf('@');
        if (at >= 0) {
            try { chance = Float.parseFloat(text.substring(at + 1).trim()); }
            catch (NumberFormatException e) {
                BlastPlaster.logger.warn("[BlastPlaster] Block conversions: '{}' has an unreadable chance, rule ignored", line);
                return null;
            }
            text = text.substring(0, at).trim();
        }
        if (chance <= 0.0f) { return null; }
        if (chance > 1.0f) { chance = 1.0f; }

        int split = text.indexOf('=');
        if (split < 1 || split == text.length() - 1) {
            BlastPlaster.logger.warn("[BlastPlaster] Block conversions: '{}' is not <source>=<result>[@chance], rule ignored", line);
            return null;
        }

        String resultText = text.substring(split + 1).trim();
        Block result;
        int resultMeta = -1;
        if (resultText.equalsIgnoreCase("nothing") || resultText.equalsIgnoreCase("air") || resultText.equalsIgnoreCase("minecraft:air")) { result = Blocks.AIR; }
        else {
            String[] resultParts = splitMeta(resultText);
            result = Block.getBlockFromName(resultParts[0]);
            if (result == null || result == Blocks.AIR) {
                BlastPlaster.logger.warn("[BlastPlaster] Block conversions: '{}' names no known result block, rule ignored", line);
                return null;
            }
            if (resultParts[1] != null) { resultMeta = Integer.parseInt(resultParts[1]); }
        }

        String source = text.substring(0, split).trim();
        if (source.startsWith("#")) {
            String oreName = source.substring(1).trim();
            if (oreName.isEmpty() || !OreDictionary.doesOreNameExist(oreName)) {
                BlastPlaster.logger.warn("[BlastPlaster] Block conversions: '{}' is not a registered ore dictionary name, rule ignored", source);
                return null;
            }
            Set<String> keys = oreKeys(oreName);
            if (keys.isEmpty()) {
                BlastPlaster.logger.warn("[BlastPlaster] Block conversions: ore dictionary name '{}' holds no blocks, rule ignored", oreName);
                return null;
            }
            return new Rule(state -> keys.contains(stateKey(state)), result, resultMeta, chance);
        }

        String[] sourceParts = splitMeta(source);
        Block block = Block.getBlockFromName(sourceParts[0]);
        if (block == null || block == Blocks.AIR) {
            BlastPlaster.logger.warn("[BlastPlaster] Block conversions: '{}' is not a known block, rule ignored", source);
            return null;
        }
        if (sourceParts[1] == null) { return new Rule(state -> state.getBlock() == block, result, resultMeta, chance); }

        int sourceMeta = Integer.parseInt(sourceParts[1]);
        return new Rule(state -> state.getBlock() == block && state.getBlock().getMetaFromState(state) == sourceMeta, result, resultMeta, chance);
    }

    private static String[] splitMeta(String text) {
        int last = text.lastIndexOf(':');
        if (last < 1) { return new String[] { text, null }; }
        String tail = text.substring(last + 1);
        try {
            Integer.parseInt(tail);
            String head = text.substring(0, last);
            if (Block.getBlockFromName(head) != null) { return new String[] { head, tail }; }
        }
        catch (NumberFormatException ignored) {}
        return new String[] { text, null };
    }

    private static Set<String> oreKeys(String oreName) {
        Set<String> keys = new HashSet<>();
        for (ItemStack stack : OreDictionary.getOres(oreName)) {
            Block block = Block.getBlockFromItem(stack.getItem());
            if (block == Blocks.AIR || block.getRegistryName() == null) { continue; }
            int firstMeta = stack.getItemDamage() == OreDictionary.WILDCARD_VALUE ? 0 : stack.getItemDamage();
            int lastMeta = stack.getItemDamage() == OreDictionary.WILDCARD_VALUE ? 15 : stack.getItemDamage();
            for (int meta = firstMeta; meta <= lastMeta; meta++) { keys.add(block.getRegistryName() + ":" + meta); }
        }
        return keys;
    }

    private static String stateKey(IBlockState state) {
        ResourceLocation name = state.getBlock().getRegistryName();
        if (name == null) { return ""; }
        return name + ":" + state.getBlock().getMetaFromState(state);
    }

    @SuppressWarnings("deprecation") private static IBlockState stateFromMeta(Block block, int meta) {
        try { return block.getStateFromMeta(meta); }
        catch (Exception e) { return block.getDefaultState(); }
    }

    private static IBlockState withPropertiesOf(IBlockState target, IBlockState source) {
        IBlockState out = target;
        for (IProperty<?> property : source.getPropertyKeys()) {
            if (out.getPropertyKeys().contains(property)) { out = copyProperty(out, source, property); }
        }
        return out;
    }

    private static <T extends Comparable<T>> IBlockState copyProperty(IBlockState target, IBlockState source, IProperty<T> property) {
        return target.withProperty(property, source.getValue(property));
    }
}
