package mctmods.blastplaster.util.compat;

import mctmods.blastplaster.BlastPlaster;
import mctmods.blastplaster.Config;
import mctmods.blastplaster.Config.ExplosionMode;
import mctmods.blastplaster.helper.BlockStatePosWrapper;
import mctmods.blastplaster.util.BlastPlasterUtil;
import mctmods.blastplaster.worldhealer.RegionSnapshotHealer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SuppressWarnings("unchecked")
public class AlexsCavesCompat {

    private static final EntityType<?> NUCLEAR_EXPLOSION = ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.fromNamespaceAndPath("alexscaves", "nuclear_explosion"));
    private static final TagKey<Block> NUKE_PROOF = TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("alexscaves", "nuke_proof"));

    private static final int TREE_COLUMN_EXTENSION = 40;
    private static final int TREE_COLUMN_AIR_GAP = 2;

    private static EntityDataAccessor<Float> SIZE_ACCESSOR = null;
    private static EntityDataAccessor<Boolean> NO_GRIEFING_ACCESSOR = null;

    private static long lastNukeProcessTick = 0;

    static {
        if (BlastPlasterUtil.AC_LOADED) { initAccessors(); }
    }

    private static void initAccessors() {
        try {
            Class<?> explosionClass = Class.forName("com.github.alexmodguy.alexscaves.server.entity.item.NuclearExplosionEntity");
            Field sizeField = explosionClass.getDeclaredField("SIZE");
            sizeField.setAccessible(true);
            SIZE_ACCESSOR = (EntityDataAccessor<Float>) sizeField.get(null);

            Field griefField = explosionClass.getDeclaredField("NO_GRIEFING");
            griefField.setAccessible(true);
            NO_GRIEFING_ACCESSOR = (EntityDataAccessor<Boolean>) griefField.get(null);
        }
        catch (Exception e) { BlastPlaster.LOGGER.warn("Alex's Caves compatibility: Reflection failed for accessors. Using safe fallbacks.", e); }
    }


    @SubscribeEvent public void onNuclearExplosionSpawn(EntityJoinLevelEvent event) {
        if (!BlastPlasterUtil.AC_LOADED || event.getLevel().isClientSide || NUCLEAR_EXPLOSION == null) { return; }
        Entity entity = event.getEntity();
        if (entity.getType() != NUCLEAR_EXPLOSION) { return; }

        ServerLevel world = (ServerLevel) event.getLevel();
        long currentTick = world.getGameTime();
        if (currentTick - lastNukeProcessTick < 2) { BlastPlaster.debug("[BlastPlaster] AC nuke: skipped, duplicate within 2 ticks"); return; }
        lastNukeProcessTick = currentTick;

        if (!world.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) { BlastPlaster.debug("[BlastPlaster] AC nuke: skipped, mobGriefing off"); return; }
        if (NO_GRIEFING_ACCESSOR != null && entity.getEntityData().get(NO_GRIEFING_ACCESSOR)) { BlastPlaster.debug("[BlastPlaster] AC nuke: skipped, entity NO_GRIEFING flag set"); return; }

        boolean shouldProcess = (Config.view(world).healAll() || Config.view(world).healNonPlayerTNT()) && Config.isAlexsCavesNukesEnabled();
        if (!shouldProcess) { BlastPlaster.debug("[BlastPlaster] AC nuke: skipped, HealAll={} HealNonPlayerTNT={} EnableAlexsCavesNukes={}", Config.view(world).healAll(), Config.view(world).healNonPlayerTNT(), Config.isAlexsCavesNukesEnabled()); return; }

        ExplosionMode mode = Config.view(world).healAll() ? ExplosionMode.HEAL : Config.view(world).getExplosionMode();

        float size = SIZE_ACCESSOR != null ? entity.getEntityData().get(SIZE_ACCESSOR) : 1.75F;
        int chunksAffected = (int) Math.ceil(size);
        int radius = chunksAffected * 15;

        BlockPos center = entity.blockPosition();
        Map<BlockPos, BlockStatePosWrapper> snapshot = new HashMap<>();

        BlockPos.MutableBlockPos carve = new BlockPos.MutableBlockPos();
        final float fixedWidth = 0.85F;

        for (int cx = -chunksAffected; cx <= chunksAffected; cx++) {
            for (int cy = -chunksAffected; cy <= chunksAffected; cy++) {
                for (int cz = -chunksAffected; cz <= chunksAffected; cz++) {
                    BlockPos chunkCorner = center.offset(cx * 16, cy * 16, cz * 16);
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            for (int y = 15; y >= 0; y--) {
                                int worldY = Mth.clamp(chunkCorner.getY() + y, world.getMinBuildHeight(), world.getMaxBuildHeight());
                                carve.set(chunkCorner.getX() + x, worldY, chunkCorner.getZ() + z);
                                if (snapshot.containsKey(carve)) { continue; }

                                double absDy = Math.abs(center.getY() - carve.getY());
                                double yDist = Math.max(0.0, 1.0 - absDy / (radius * 1.5));
                                double distToCenterSqr = carve.distToLowCornerSqr(center.getX(), carve.getY() - 1.0, center.getZ());
                                double targetRadiusSqr = yDist * (radius + fixedWidth * radius) * radius;

                                if (distToCenterSqr <= targetRadiusSqr) {
                                    BlockState state = world.getBlockState(carve);
                                    if (state.isAir()) { continue; }
                                    if (state.is(NUKE_PROOF)) { continue; }
                                    if (state.getBlock() == Blocks.TNT) { continue; }
                                    BlockPos immutable = carve.immutable();
                                    snapshot.put(immutable, new BlockStatePosWrapper(world, immutable, state));
                                }
                            }
                        }
                    }
                }
            }
        }

        if (snapshot.isEmpty()) { BlastPlaster.debug("[BlastPlaster] AC nuke: snapshot empty at {} (size {}), nothing to track", center, size); return; }

        List<BlockPos> columnSeeds = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockStatePosWrapper> e : snapshot.entrySet()) {
            BlockState s = e.getValue().getState();
            if (s.is(BlockTags.LOGS) || s.getBlock() instanceof LeavesBlock || BlastPlasterUtil.isDynamicTreesAssembly(s)) {
                if (!snapshot.containsKey(e.getKey().above())) { columnSeeds.add(e.getKey()); }
            }
        }

        int extended = 0;
        BlockPos.MutableBlockPos up = new BlockPos.MutableBlockPos();
        for (BlockPos seed : columnSeeds) {
            int airGap = 0;
            for (int dy = 1; dy <= TREE_COLUMN_EXTENSION && airGap <= TREE_COLUMN_AIR_GAP; dy++) {
                int yy = seed.getY() + dy;
                if (yy > world.getMaxBuildHeight() - 1) { break; }
                up.set(seed.getX(), yy, seed.getZ());
                if (snapshot.containsKey(up)) { airGap = 0; continue; }
                BlockState state = world.getBlockState(up);
                if (state.isAir()) { airGap++; continue; }
                airGap = 0;
                if (state.getBlock() == Blocks.TNT) { continue; }
                BlockPos immutable = up.immutable();
                snapshot.put(immutable, new BlockStatePosWrapper(world, immutable, state));
                extended++;
            }
        }

        if (Config.view(world).enableDropSuppression()) { BlastPlasterUtil.recordExplosionArea(world, snapshot.keySet(), mode == ExplosionMode.HEAL); }

        int chunkCount = (2 * chunksAffected + 1) * (2 * chunksAffected + 1) * (2 * chunksAffected + 1);
        int carveDuration = chunkCount / 3 + 60;
        int passes = Math.max(12, carveDuration / 20 + 3);
        RegionSnapshotHealer.scheduleDiffHeal(world, snapshot, 20, 20, passes, mode, BlastPlasterUtil.ALEXSCAVES_NUKE_VISUAL_CHANCE);
        BlastPlaster.debug("[BlastPlaster] AC nuke: snapshot {} blocks (+{} tree column) at {} (mode {}), {} diff passes scheduled", snapshot.size(), extended, center, mode, passes);
    }
}
