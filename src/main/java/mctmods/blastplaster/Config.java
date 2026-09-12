package mctmods.blastplaster;

import net.minecraft.block.Block;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IStringSerializable;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;
import net.minecraftforge.oredict.OreDictionary;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public class Config {

    public enum ExplosionMode { HEAL, EJECT_DROPS, VISUAL_TOSS }

    private static final String CAT_EXPLOSION = "explosion";
    private static final String CAT_HEALING = "healing";
    private static final String CAT_SOURCES = "explosion_sources";
    private static final String CAT_EJECT = "eject_drops";
    private static final String CAT_MOB_DROPS = "mob_drops";
    private static final String CAT_TREES = "trees";
    private static final String CAT_CONVERSIONS = "conversions";
    private static final Map<String, String> TREE_MAP = new HashMap<>();
    private static final Map<IBlockState, String> LOG_KEY_CACHE = new IdentityHashMap<>();
    private static final Map<IBlockState, String> LEAF_KEY_CACHE = new IdentityHashMap<>();
    private static final String NO_KEY = "";
    private static final Set<String> LOG_KEYS = new HashSet<>();
    private static final Set<String> LEAF_KEYS = new HashSet<>();
    private static final List<String> CUSTOM_ENTITIES = new ArrayList<>();
    private static String[] treeLogLeafPairs = new String[0];
    private static String[] blockConversions = new String[0];
    private static ExplosionMode explosionMode = ExplosionMode.HEAL;
    private static boolean enableFakeTossedBlocks;
    private static boolean enableExplosionFlash;
    private static int explosionFlashDuration;
    private static int explosionFlashLightLevel;
    private static int explosionFlashParticleCount;
    private static int explosionFlashPulses;
    private static boolean enableExplosionSmoke;
    private static int explosionSmokeDuration;
    private static int explosionSmokeParticleCount;
    private static boolean playerTNTAlwaysDrops;
    private static boolean playerTNTDropFullBlocks;
    private static int minTicksBeforeHeal;
    private static int randomTickVar;
    private static boolean overrideBlocks;
    private static boolean healFullTrees;
    private static boolean healCreepers;
    private static boolean healNonPlayerTNT;
    private static boolean healWither;
    private static boolean healAll;
    private static boolean processPlayerIgnitedTNT;
    private static boolean dtSpecialDrops;
    private static boolean enableDropSuppression;
    private static boolean preventMobDrops;
    private static int maxTreeSize;
    private static boolean debugLogging;
    private static boolean treeMapBuilt;

    private Config() {}

    public interface View {
        default ExplosionMode getExplosionMode() { return Config.getExplosionMode(); }

        default boolean enableFakeTossedBlocks() { return Config.enableFakeTossedBlocks(); }

        default boolean enableExplosionFlash() { return Config.enableExplosionFlash(); }

        default int getExplosionFlashDuration() { return Config.getExplosionFlashDuration(); }

        default int getExplosionFlashLightLevel() { return Config.getExplosionFlashLightLevel(); }

        default int getExplosionFlashParticleCount() { return Config.getExplosionFlashParticleCount(); }

        default int getExplosionFlashPulses() { return Config.getExplosionFlashPulses(); }

        default boolean enableExplosionSmoke() { return Config.enableExplosionSmoke(); }

        default int getExplosionSmokeDuration() { return Config.getExplosionSmokeDuration(); }

        default int getExplosionSmokeParticleCount() { return Config.getExplosionSmokeParticleCount(); }

        default boolean playerTNTAlwaysDrops() { return Config.playerTNTAlwaysDrops(); }

        default boolean playerTNTDropFullBlocks() { return Config.playerTNTDropFullBlocks(); }

        default boolean healCreepers() { return Config.healCreepers(); }

        default boolean healNonPlayerTNT() { return Config.healNonPlayerTNT(); }

        default boolean healWither() { return Config.healWither(); }

        default boolean healAll() { return Config.healAll(); }

        default boolean processPlayerIgnitedTNT() { return Config.processPlayerIgnitedTNT(); }

        default List<String> getCustomEntitiesToHeal() { return Config.getCustomEntitiesToHeal(); }

        default int getMinimumTicksBeforeHeal() { return Config.getMinimumTicksBeforeHeal(); }

        default int getRandomTickVar() { return Config.getRandomTickVar(); }

        default boolean isOverride() { return Config.isOverride(); }

        default boolean healFullTrees() { return Config.healFullTrees(); }

        default boolean dtSpecialDrops() { return Config.dtSpecialDrops(); }

        default int getMaxTreeSize() { return Config.getMaxTreeSize(); }

        default List<String> getBlockConversions() { return Config.getBlockConversions(); }

        default boolean enableDropSuppression() { return Config.enableDropSuppression(); }

        default boolean preventMobDrops() { return Config.preventMobDrops(); }
    }

    private static final View GLOBAL = new View() {};
    private static Function<World, View> provider;

    @SuppressWarnings("unused")
    public static void provider(Function<World, View> now) { provider = now; }

    public static View view(World world) {
        if (provider == null || world == null) { return GLOBAL; }

        View given = provider.apply(world);
        return given == null ? GLOBAL : given;
    }

    public static View view(IBlockAccess access) { return access instanceof World ? view((World) access) : GLOBAL; }

    public static void syncConfig() {
        Configuration config = BlastPlaster.config;
        try {
            config.load();

            config.setCategoryComment(CAT_EXPLOSION, "Explosion mode and visuals. When MCT Resource Data Pack Loader is installed and driving Blast Plaster, packs override these values, per dimension if they choose; keys no pack sets keep the values here");
            Property modeProperty = config.get(CAT_EXPLOSION, "ExplosionMode", ExplosionMode.HEAL.name(),
                    "HEAL (default): blocks disappear then slowly restore + fake tossed blocks fly out\n"
                            + "EJECT_DROPS: blocks gone forever, real drops with 1/3 chance per block (exactly matching vanilla creeper), otherwise fake tossed block instead (fakes exactly make up the remaining ~2/3, 100% visual coverage)\n"
                            + "VISUAL_TOSS: blocks gone forever, only fake tossed blocks fly out (no real drops, no restore)");
            modeProperty.setValidValues(modeNames());
            explosionMode = parseMode(modeProperty.getString());
            enableFakeTossedBlocks = config.get(CAT_EXPLOSION, "EnableFakeTossedBlocks", true,
                    "Spawn visible 3D fake block entities that fly out and despawn after ~3 seconds (no pickup, no placement). In EJECT_DROPS mode these exactly fill the blocks that do NOT drop real items. Ignored in modes where not applicable.").getBoolean();
            enableExplosionFlash = config.get(CAT_EXPLOSION, "EnableExplosionFlash", true,
                    "Enable temporary light source flash at explosion center (light block + many bright flash particles) for realism - visible even in full daylight.").getBoolean();
            explosionFlashDuration = config.get(CAT_EXPLOSION, "ExplosionFlashDuration", 28,
                    "Server ticks the flash light block remains visible (placed AFTER blocks are cleared to AIR; 4-12 recommended).", 1, 40).getInt();
            explosionFlashLightLevel = config.get(CAT_EXPLOSION, "ExplosionFlashLightLevel", 15,
                    "Light level of the flash (0-15).", 0, 15).getInt();
            explosionFlashParticleCount = config.get(CAT_EXPLOSION, "ExplosionFlashParticleCount", 240,
                    "Number of bright flash particles for explosion visual flash (higher values = stronger camera-flash pop that overpowers ambient light on surrounding blocks).", 20, 600).getInt();
            explosionFlashPulses = config.get(CAT_EXPLOSION, "ExplosionFlashPulses", 4,
                    "Number of staggered flash particle bursts (1 = single burst, 4 = dramatic camera-flash strobe/overexposure that cuts through torches/ambient light).", 1, 6).getInt();
            enableExplosionSmoke = config.get(CAT_EXPLOSION, "EnableExplosionSmoke", true,
                    "Enable large poof of smoke rising from explosion center (lingering realistic smoke cloud visible in ALL modes, even daylight).").getBoolean();
            explosionSmokeDuration = config.get(CAT_EXPLOSION, "ExplosionSmokeDuration", 200,
                    "Total server ticks the smoke poof effect continues (200 ticks = 10 seconds; bursts every 15 ticks).", 40, 1200).getInt();
            explosionSmokeParticleCount = config.get(CAT_EXPLOSION, "ExplosionSmokeParticleCount", 1,
                    "Smoke particles per burst (first burst doubled for initial outward poof; total ~15 particles over 10s at default; higher = thicker rising column).", 1, 10).getInt();
            playerTNTAlwaysDrops = config.get(CAT_EXPLOSION, "PlayerTNTAlwaysDrops", true,
                    "If true (default), player-ignited TNT explosions ALWAYS use EJECT_DROPS behavior (real item drops + permanent removal) regardless of global ExplosionMode. Set false to respect the selected global mode. NOTE: Requires ProcessPlayerIgnitedTNT=true to have any effect.").getBoolean();
            playerTNTDropFullBlocks = config.get(CAT_EXPLOSION, "PlayerTNTDropFullBlocks", false,
                    "If true, player-ignited TNT drops the full block item (silk-touch like). Only applies when PlayerTNTAlwaysDrops=true and effective mode is EJECT_DROPS. DT blocks prioritize DynamicTreesSpecialDrops if enabled.").getBoolean();

            config.setCategoryComment(CAT_HEALING, "Healing settings (HEAL mode only). When MCT Resource Data Pack Loader is installed and driving Blast Plaster, packs override these values, per dimension if they choose; keys no pack sets keep the values here");
            minTicksBeforeHeal = config.get(CAT_HEALING, "TickStartDelay", 600,
                    "Minimum ticks before healing begins after explosion. Only used in HEAL mode.", 1, 600000).getInt();
            randomTickVar = config.get(CAT_HEALING, "TickRandomInterval", 200,
                    "Random extra ticks added per healing layer. Only used in HEAL mode.", 1, 600000).getInt();
            overrideBlocks = config.get(CAT_HEALING, "OverrideBlocks", true,
                    "Replace any block (including fluids) when healing.").getBoolean();
            healFullTrees = config.get(CAT_HEALING, "HealFullTrees", true,
                    "When a tree is partially exploded, heal the entire tree. Enables tree expansion logic.").getBoolean();

            config.setCategoryComment(CAT_SOURCES, "Which explosions to process. When MCT Resource Data Pack Loader is installed and driving Blast Plaster, packs override these values, per dimension if they choose; keys no pack sets keep the values here");
            healCreepers = config.get(CAT_SOURCES, "HealCreepers", true,
                    "Process creeper explosions according to selected mode.").getBoolean();
            healNonPlayerTNT = config.get(CAT_SOURCES, "HealNonPlayerTNT", true,
                    "Process TNT explosions not ignited by players according to selected mode.").getBoolean();
            healWither = config.get(CAT_SOURCES, "HealWither", true,
                    "Process wither explosions according to selected mode.").getBoolean();
            healAll = config.get(CAT_SOURCES, "HealAll", false,
                    "Process EVERY explosion regardless of source (overrides all specific options above).").getBoolean();
            processPlayerIgnitedTNT = config.get(CAT_SOURCES, "ProcessPlayerIgnitedTNT", true,
                    "If true (default), player-ignited TNT explosions are processed (subject to PlayerTNTAlwaysDrops). Set false to completely ignore player TNT like vanilla.").getBoolean();
            CUSTOM_ENTITIES.clear();
            CUSTOM_ENTITIES.addAll(Arrays.asList(config.get(CAT_SOURCES, "CustomEntitiesToHeal", new String[0],
                    "Extra entity IDs (modid:entity) whose explosions should be processed.").getStringList()));

            config.setCategoryComment(CAT_EJECT, "EJECT_DROPS settings. When MCT Resource Data Pack Loader is installed and driving Blast Plaster, packs override these values, per dimension if they choose; keys no pack sets keep the values here");
            dtSpecialDrops = config.get(CAT_EJECT, "DynamicTreesSpecialDrops", true,
                    "For Dynamic Trees blocks in EJECT_DROPS mode: drop the tree's primitive log + sticks instead of full DT items. Applies to all EJECT_DROPS including player TNT (overrides full blocks intent for DT).").getBoolean();
            enableDropSuppression = config.get(CAT_EJECT, "EnableDropSuppression", true,
                    "Prevents ANY stray item entities (seeds, sticks, vines, etc.) from ever spawning in HEAL or VISUAL_TOSS modes by cancelling them the instant they try to join the world. This is the core safety system for non-EJECT modes (no late scavenging anymore). Default true.").getBoolean();

            config.setCategoryComment(CAT_MOB_DROPS, "Mob drops. When MCT Resource Data Pack Loader is installed and driving Blast Plaster, packs override these values, per dimension if they choose; keys no pack sets keep the values here");
            preventMobDrops = config.get(CAT_MOB_DROPS, "PreventMobDrops", false,
                    "If true, prevent ALL drops from mobs/entities killed by ANY explosion (in every mode). Vanilla drops are cancelled. Default false (allow drops - they are automatically protected from suppression so they survive in HEAL/VISUAL_TOSS modes).").getBoolean();

            config.setCategoryComment(CAT_TREES, "Tree and misc helpers");
            treeLogLeafPairs = config.get(CAT_TREES, "TreeLogLeafPairs", new String[0],
                    "Custom tree log<->leaf pairings for full tree healing.\n"
                            + "Format: modid:log_block:meta=modid:leaf_block:meta (meta defaults to 0 when omitted)\n"
                            + "Only needed for trees that the ore dictionary scan cannot pair by name.").getStringList();
            maxTreeSize = config.get(CAT_TREES, "MaxTreeSize", 20000,
                    "Max blocks allowed in a tree for full healing (prevents lag). Large old-growth jungle trees often exceed 7500 blocks; set higher if you see warnings.", 0, 50000).getInt();

            config.setCategoryComment(CAT_CONVERSIONS, "Block conversions");
            blockConversions = config.get(CAT_CONVERSIONS, "BlockConversions", new String[0],
                    "Convert blocks caught in an explosion instead of putting them back or dropping them as they were,\n"
                            + "so a structure degrades a step per blast.\n"
                            + "Format: <source>=<result>[@chance], one rule per entry. Example: minecraft:stone=minecraft:cobblestone@0.75\n"
                            + "Source is a block id (minecraft:stone), a block id with a meta (minecraft:log:1),\n"
                            + "or an ore dictionary name with a leading # (#logWood).\n"
                            + "A source with no meta matches every meta of that block.\n"
                            + "Result is a block id, a block id with a meta, or 'nothing' to leave the position empty.\n"
                            + "A result with no meta keeps the properties it shares with the source, so a converted log keeps its axis;\n"
                            + "a result with a meta uses that meta exactly and copies nothing.\n"
                            + "Chance is 0.0-1.0 and defaults to 1.0. A rule that matches but fails its roll leaves the block alone;\n"
                            + "later rules are not tried. The first matching rule wins, so put the specific rules above the ore names.\n"
                            + "A block that is already the result of some rule is never converted, so a second explosion does not\n"
                            + "degrade it further.").getStringList();

            debugLogging = config.get(Configuration.CATEGORY_GENERAL, "EnableDebugLogging", false,
                    "If true, BlastPlaster prints detailed heal scheduling and batch telemetry to the log.").getBoolean();

            treeMapBuilt = false;
            validateConfig();
        }
        catch (Exception e) { BlastPlaster.logger.error("Config Error {}", String.valueOf(e)); }
        finally { if (config.hasChanged()) { config.save(); }}
    }

    private static void validateConfig() {
        if (playerTNTAlwaysDrops && !processPlayerIgnitedTNT) { BlastPlaster.logger.warn("Config: PlayerTNTAlwaysDrops=true but ProcessPlayerIgnitedTNT=false - player TNT explosions will be ignored."); }
    }

    private static String[] modeNames() {
        ExplosionMode[] modes = ExplosionMode.values();
        String[] names = new String[modes.length];
        for (int i = 0; i < modes.length; i++) { names[i] = modes[i].name(); }
        return names;
    }

    private static ExplosionMode parseMode(String value) {
        for (ExplosionMode mode : ExplosionMode.values()) { if (mode.name().equalsIgnoreCase(value)) { return mode; }}
        return ExplosionMode.HEAL;
    }

    public static ExplosionMode getExplosionMode() { return explosionMode; }

    public static boolean enableFakeTossedBlocks() { return enableFakeTossedBlocks; }

    public static boolean enableExplosionFlash() { return enableExplosionFlash; }

    public static int getExplosionFlashDuration() { return explosionFlashDuration; }

    public static int getExplosionFlashLightLevel() { return explosionFlashLightLevel; }

    public static int getExplosionFlashParticleCount() { return explosionFlashParticleCount; }

    public static int getExplosionFlashPulses() { return explosionFlashPulses; }

    public static boolean enableExplosionSmoke() { return enableExplosionSmoke; }

    public static int getExplosionSmokeDuration() { return explosionSmokeDuration; }

    public static int getExplosionSmokeParticleCount() { return explosionSmokeParticleCount; }

    public static boolean playerTNTAlwaysDrops() { return playerTNTAlwaysDrops; }

    public static boolean playerTNTDropFullBlocks() { return playerTNTDropFullBlocks; }

    public static boolean healCreepers() { return healCreepers; }

    public static boolean healNonPlayerTNT() { return healNonPlayerTNT; }

    public static boolean healWither() { return healWither; }

    public static boolean healAll() { return healAll; }

    public static boolean processPlayerIgnitedTNT() { return processPlayerIgnitedTNT; }

    public static List<String> getCustomEntitiesToHeal() { return CUSTOM_ENTITIES; }

    public static int getMinimumTicksBeforeHeal() { return minTicksBeforeHeal; }

    public static int getRandomTickVar() { return randomTickVar; }

    public static boolean isOverride() { return overrideBlocks; }

    public static boolean debugLogging() { return debugLogging; }

    public static boolean healFullTrees() { return healFullTrees; }

    public static boolean dtSpecialDrops() { return dtSpecialDrops; }

    public static int getMaxTreeSize() { return maxTreeSize; }

    public static List<String> getBlockConversions() { return Arrays.asList(blockConversions); }

    public static boolean enableDropSuppression() { return enableDropSuppression; }

    public static boolean preventMobDrops() { return preventMobDrops; }

    public static boolean isLog(IBlockState state) { return getLogKey(state) != null; }

    public static String getLogKey(IBlockState state) {
        buildTreeMap();
        String cached = LOG_KEY_CACHE.get(state);
        if (cached == null) {
            String key = variantKey(state);
            cached = LOG_KEYS.contains(key) ? key : NO_KEY;
            LOG_KEY_CACHE.put(state, cached);
        }
        return cached.isEmpty() ? null : cached;
    }

    public static String getLeavesKey(IBlockState state) {
        buildTreeMap();
        String cached = LEAF_KEY_CACHE.get(state);
        if (cached == null) {
            String key = variantKey(state);
            cached = LEAF_KEYS.contains(key) ? key : NO_KEY;
            LEAF_KEY_CACHE.put(state, cached);
        }
        return cached.isEmpty() ? null : cached;
    }

    public static String getLeavesKeyForLog(String logKey) {
        buildTreeMap();
        return TREE_MAP.get(logKey);
    }

    private static String variantKey(IBlockState state) {
        Block block = state.getBlock();
        ResourceLocation name = block.getRegistryName();
        if (name == null) { return ""; }
        return name + ":" + block.damageDropped(state);
    }

    private static String blockName(String key) { return key.substring(0, key.lastIndexOf(':')); }

    private static void buildTreeMap() {
        if (treeMapBuilt) { return; }
        treeMapBuilt = true;
        TREE_MAP.clear();
        LOG_KEYS.clear();
        LEAF_KEYS.clear();
        LOG_KEY_CACHE.clear();
        LEAF_KEY_CACHE.clear();
        Map<String, String> logNames = new HashMap<>();
        Map<String, String> leafNames = new HashMap<>();
        collectOreKeys("logWood", LOG_KEYS, logNames);
        collectOreKeys("treeLeaves", LEAF_KEYS, leafNames);
        int namePairs = 0;
        for (Map.Entry<String, String> entry : logNames.entrySet()) {
            String leafKey = leafNames.get(entry.getKey());
            if (leafKey != null) {
                TREE_MAP.put(entry.getValue(), leafKey);
                namePairs++;
            }
        }
        BlastPlaster.debug("Paired {} log/leaf types by variant name", namePairs);
        addPair(Blocks.LOG, 0, Blocks.LEAVES, 0);
        addPair(Blocks.LOG, 1, Blocks.LEAVES, 1);
        addPair(Blocks.LOG, 2, Blocks.LEAVES, 2);
        addPair(Blocks.LOG, 3, Blocks.LEAVES, 3);
        addPair(Blocks.LOG2, 0, Blocks.LEAVES2, 4);
        addPair(Blocks.LOG2, 1, Blocks.LEAVES2, 5);
        pairByName();
        applyConfigPairs();
        reportUnpaired();
    }

    @SuppressWarnings("deprecation") private static void collectOreKeys(String oreName, Set<String> out, Map<String, String> namesOut) {
        for (ItemStack stack : OreDictionary.getOres(oreName)) {
            Block block = Block.getBlockFromItem(stack.getItem());
            if (block == Blocks.AIR) { continue; }
            if (block.getRegistryName() == null) { continue; }
            int firstMeta = stack.getItemDamage() == OreDictionary.WILDCARD_VALUE ? 0 : stack.getItemDamage();
            int lastMeta = stack.getItemDamage() == OreDictionary.WILDCARD_VALUE ? 15 : stack.getItemDamage();
            for (int meta = firstMeta; meta <= lastMeta; meta++) {
                IBlockState state;
                try { state = block.getStateFromMeta(meta); }
                catch (Exception e) { continue; }
                String key = variantKey(state);
                out.add(key);
                String variantName = variantName(state);
                if (variantName != null && !namesOut.containsKey(variantName)) { namesOut.put(variantName, key); }
            }
        }
    }

    private static String variantName(IBlockState state) {
        for (IProperty<?> property : state.getPropertyKeys()) {
            if (!"variant".equals(property.getName())) { continue; }
            Comparable<?> value = state.getValue(property);
            if (value instanceof IStringSerializable) { return ((IStringSerializable) value).getName(); }
            return value.toString().toLowerCase(Locale.ROOT);
        }
        return null;
    }

    private static void addPair(Block log, int logMeta, Block leaves, int leavesMeta) {
        String logKey = log.getRegistryName() + ":" + logMeta;
        String leavesKey = leaves.getRegistryName() + ":" + leavesMeta;
        LOG_KEYS.add(logKey);
        LEAF_KEYS.add(leavesKey);
        TREE_MAP.put(logKey, leavesKey);
    }

    private static void pairByName() {
        for (String logKey : LOG_KEYS) {
            if (TREE_MAP.containsKey(logKey)) { continue; }
            int split = logKey.lastIndexOf(':');
            String name = logKey.substring(0, split);
            if (!name.endsWith("_log")) { continue; }
            String candidate = name.substring(0, name.length() - 4) + "_leaves" + logKey.substring(split);
            if (LEAF_KEYS.contains(candidate)) { TREE_MAP.put(logKey, candidate); }
        }
    }

    private static void applyConfigPairs() {
        for (String pair : treeLogLeafPairs) {
            String[] parts = pair.split("=");
            if (parts.length != 2) { continue; }
            String logKey = normalizeKey(parts[0]);
            String leavesKey = normalizeKey(parts[1]);
            if (logKey == null || leavesKey == null) { continue; }
            LOG_KEYS.add(logKey);
            LEAF_KEYS.add(leavesKey);
            TREE_MAP.put(logKey, leavesKey);
        }
    }

    private static String normalizeKey(String entry) {
        String value = entry.trim();
        int count = 0;
        for (int i = 0; i < value.length(); i++) { if (value.charAt(i) == ':') { count++; }}
        if (count == 1) { return value + ":0"; }
        if (count == 2) { return value; }
        return null;
    }

    private static void reportUnpaired() {
        Set<String> paired = new HashSet<>();
        for (String logKey : TREE_MAP.keySet()) { paired.add(blockName(logKey)); }
        Set<String> reported = new HashSet<>();
        for (String logKey : LOG_KEYS) {
            String name = blockName(logKey);
            if (paired.contains(name) || !reported.add(name)) { continue; }
            BlastPlaster.debug("No leaf pairing found for log {}", name);
        }
    }
}
