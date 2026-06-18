package mctmods.blastplaster.util.compat;

import com.dtteam.dynamictrees.api.network.MapSignal;
import com.dtteam.dynamictrees.block.branch.BasicRootsBlock;
import com.dtteam.dynamictrees.tree.TreeHelper;
import mctmods.blastplaster.BlastPlaster;
import mctmods.blastplaster.Config;
import mctmods.blastplaster.Config.ExplosionMode;
import mctmods.blastplaster.helper.BlockStatePosWrapper;
import mctmods.blastplaster.util.BlastPlasterUtil;
import mctmods.blastplaster.worldhealer.WorldHealerSaveDataSupplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

import net.neoforged.fml.ModList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@SuppressWarnings("unchecked")
public class AlexsCavesCompat {

    private static final EntityType<?> NUCLEAR_EXPLOSION;
    private static final TagKey<Block> NUKE_PROOF;
    private static final Block TREMORZILLA_EGG;

    private static net.minecraft.network.syncher.EntityDataAccessor<Float> SIZE_ACCESSOR = null;
    private static Method spawnDinosaursMethod = null;

    private static long lastNukeProcessTick = 0;

    static {
        Identifier nukeId = Identifier.tryParse("alexscaves:nuclear_explosion");
        if (nukeId != null) {
            ResourceKey<EntityType<?>> nukeKey = ResourceKey.create(Registries.ENTITY_TYPE, nukeId);
            NUCLEAR_EXPLOSION = BuiltInRegistries.ENTITY_TYPE.getOptional(nukeKey).orElse(null);
        } else {
            NUCLEAR_EXPLOSION = null;
        }

        Identifier nukeProofId = Identifier.tryParse("alexscaves:nuke_proof");
        if (nukeProofId != null) {
            NUKE_PROOF = TagKey.create(Registries.BLOCK, nukeProofId);
        } else {
            NUKE_PROOF = null;
        }

        Identifier eggId = Identifier.tryParse("alexscaves:tremorzilla_egg");
        if (eggId != null) {
            ResourceKey<Block> eggKey = ResourceKey.create(Registries.BLOCK, eggId);
            TREMORZILLA_EGG = BuiltInRegistries.BLOCK.getOptional(eggKey).orElse(null);
        } else {
            TREMORZILLA_EGG = null;
        }

        try {
            Class<?> explosionClass = Class.forName("com.github.alexmodguy.alexscaves.server.entity.item.NuclearExplosionEntity");
            Field sizeField = explosionClass.getDeclaredField("SIZE");
            sizeField.setAccessible(true);
            SIZE_ACCESSOR = (net.minecraft.network.syncher.EntityDataAccessor<Float>) sizeField.get(null);

            Class<?> eggClass = Class.forName("com.github.alexmodguy.alexscaves.server.block.TremorzillaEggBlock");
            spawnDinosaursMethod = eggClass.getMethod("spawnDinosaurs", Level.class, BlockPos.class, BlockState.class);
        } catch (Exception e) {
            BlastPlaster.LOGGER.warn("Alex's Caves compatibility: Reflection failed for accessors/methods.", e);
        }
    }

    @SubscribeEvent public void onNuclearExplosionSpawn(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || NUCLEAR_EXPLOSION == null) { return; }
        Entity entity = event.getEntity();
        if (entity.getType() != NUCLEAR_EXPLOSION) { return; }

        ServerLevel world = (ServerLevel) event.getLevel();
        long currentTick = world.getGameTime();
        if (currentTick - lastNukeProcessTick < 2) { return; }
        lastNukeProcessTick = currentTick;

        if (!world.getGameRules().get(GameRules.MOB_GRIEFING)) { return; }

        boolean shouldProcess = (Config.healAll() || Config.healNonPlayerTNT()) && Config.isAlexsCavesNukesEnabled();
        if (!shouldProcess) { return; }

        ExplosionMode mode = Config.getExplosionMode();
        float size = SIZE_ACCESSOR != null ? entity.getEntityData().get(SIZE_ACCESSOR) : 1.75F;
        int chunksAffected = (int) Math.ceil(size);
        int radius = chunksAffected * 15;

        BlockPos center = entity.blockPosition();
        List<BlockStatePosWrapper> toProcess = new ArrayList<>();
        Set<BlockPos> affectedPos = new HashSet<>();

        BlockPos.MutableBlockPos carve = new BlockPos.MutableBlockPos();
        final float fixedWidth = 0.85F;

        for (int cx = -chunksAffected; cx <= chunksAffected; cx++) {
            for (int cy = -chunksAffected; cy <= chunksAffected; cy++) {
                for (int cz = -chunksAffected; cz <= chunksAffected; cz++) {
                    BlockPos chunkCorner = center.offset(cx * 16, cy * 16, cz * 16);
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            for (int y = 15; y >= 0; y--) {
                                int worldY = Mth.clamp(chunkCorner.getY() + y, world.getMinY(), world.getMaxY());
                                carve.set(chunkCorner.getX() + x, worldY, chunkCorner.getZ() + z);

                                double absDy = Math.abs(center.getY() - carve.getY());
                                double yDist = Math.max(0.0, 1.0 - absDy / (radius * 1.5));
                                double distToCenterSqr = carve.distToLowCornerSqr(center.getX(), carve.getY() - 1.0, center.getZ());
                                double targetRadiusSqr = yDist * (radius + fixedWidth * radius) * radius;

                                if (distToCenterSqr <= targetRadiusSqr) {
                                    BlockState state = world.getBlockState(carve);
                                    @SuppressWarnings("deprecation")
                                    float resistance = state.getBlock().getExplosionResistance();
                                    boolean destroyable = (NUKE_PROOF == null || !state.is(NUKE_PROOF)) &&
                                            (resistance < 3600000.0) ||
                                            state.getBlock() == TREMORZILLA_EGG;
                                    if (destroyable && (!state.isAir() || !state.getFluidState().isEmpty())) {
                                        toProcess.add(new BlockStatePosWrapper(world, carve.immutable(), state));
                                        affectedPos.add(carve.immutable());

                                        if (state.getBlock() == TREMORZILLA_EGG && spawnDinosaursMethod != null) {
                                            try { spawnDinosaursMethod.invoke(state.getBlock(), world, carve, state); }
                                            catch (Exception e) { BlastPlaster.LOGGER.warn("Failed to invoke spawnDinosaurs for TremorzillaEggBlock.", e); }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (toProcess.isEmpty()) { return; }

        if (Config.healFullTrees() && ModList.get().isLoaded("dynamictrees")) {
            addFullTreeExpansion(toProcess, affectedPos, world);
        }

        if (mode == ExplosionMode.HEAL) {
            WorldHealerSaveDataSupplier worldHealer = BlastPlaster.getWorldHealer(world);
            if (worldHealer != null) {
                worldHealer.addMultiBlockStructures(toProcess, affectedPos, world);
                worldHealer.prepareAndScheduleHealing(toProcess, world);
            }
        }

        if (mode != ExplosionMode.EJECT_DROPS) {
            BlastPlasterUtil.addAttachedCocoaPods(toProcess, affectedPos, world);
            BlastPlasterUtil.addBambooVerticals(toProcess, affectedPos, world);
        }

        if (mode != ExplosionMode.EJECT_DROPS && Config.enableDropSuppression()) {
            BlastPlasterUtil.recordExplosionArea(world, affectedPos);
        }

        for (BlockStatePosWrapper wrapper : toProcess) {
            BlockPos pos = wrapper.getPos();
            BlockState state = wrapper.getState();

            boolean realDrop = false;
            if (mode == ExplosionMode.EJECT_DROPS) {
                realDrop = BlastPlasterUtil.calculateRealDrop(world);
                if (realDrop) {
                    if (BlastPlasterUtil.isDynamicTrees(state) && Config.dtSpecialDrops()) { BlastPlasterUtil.spawnDynamicTreesDrops(world, pos, state); }
                    else {
                        BlockEntity be = world.getBlockEntity(pos);
                        LootParams.Builder builder = new LootParams.Builder(world)
                                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
                                .withParameter(LootContextParams.TOOL, ItemStack.EMPTY)
                                .withOptionalParameter(LootContextParams.BLOCK_ENTITY, be)
                                .withOptionalParameter(LootContextParams.THIS_ENTITY, entity)
                                .withParameter(LootContextParams.EXPLOSION_RADIUS, radius * 0.6F);
                        for (ItemStack stack : state.getDrops(builder)) {
                            if (stack.isEmpty()) { continue; }
                            ItemEntity item = new ItemEntity(world, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack);
                            BlastPlasterUtil.applyTossVelocity(item, world);
                            world.addFreshEntity(item);
                        }
                    }
                }
            }

            float visualChance = BlastPlasterUtil.getVisualSpawnChance(false, true);
            BlastPlasterUtil.finalizeExplodedBlock(world, pos, state, mode, realDrop, visualChance);
        }
    }

    private static void addFullTreeExpansion(List<BlockStatePosWrapper> toProcess, Set<BlockPos> affectedPos, ServerLevel level) {
        if (!ModList.get().isLoaded("dynamictrees")) { return; }

        Set<BlockPos> dtTreePos = new HashSet<>();

        for (BlockPos pos : new HashSet<>(affectedPos)) {
            BlockState state = level.getBlockState(pos);
            if (TreeHelper.isBranch(state) || TreeHelper.isLeaves(state) || TreeHelper.isRooty(state) || state.getBlock() instanceof BasicRootsBlock) {
                BlockPos rootPos = TreeHelper.findRootNode(level, pos);
                if (rootPos != BlockPos.ZERO) {
                    CollectorNode collector = new CollectorNode(dtTreePos);
                    TreeHelper.startAnalysisFromRoot(level, rootPos, new MapSignal(collector));
                }
            }
        }

        for (BlockPos pos : new HashSet<>(affectedPos)) {
            for (int dx = -12; dx <= 12; dx++) {
                for (int dy = -10; dy <= 6; dy++) {
                    for (int dz = -12; dz <= 12; dz++) {
                        BlockPos candidate = pos.offset(dx, dy, dz);
                        if (affectedPos.contains(candidate)) continue;
                        BlockState s = level.getBlockState(candidate);
                        if (TreeHelper.isBranch(s) || TreeHelper.isLeaves(s) || TreeHelper.isRooty(s) || s.getBlock() instanceof BasicRootsBlock) {
                            dtTreePos.add(candidate);
                        }
                    }
                }
            }
        }

        if (dtTreePos.size() > Config.getMaxTreeSize()) {
            BlastPlaster.LOGGER.info("[BlastPlaster] [AlexsCaves] Skipped huge DT expansion ({} blocks)", dtTreePos.size());
            return;
        }

        int addedCount = 0;
        for (BlockPos p : dtTreePos) {
            if (!affectedPos.contains(p)) {
                toProcess.add(new BlockStatePosWrapper(level, p, level.getBlockState(p)));
                affectedPos.add(p);
                addedCount++;
            }
        }
        BlastPlaster.LOGGER.info("[BlastPlaster] [AlexsCaves] DT expansion added {} new blocks", addedCount);
    }

    private record CollectorNode(Set<BlockPos> nodeSet) implements com.dtteam.dynamictrees.api.network.NodeInspector {
        @Override public boolean run(BlockState state, LevelAccessor level, BlockPos pos, Direction fromDir) {
            nodeSet.add(pos.immutable());
            return true;
        }
        @Override public boolean returnRun(BlockState state, LevelAccessor level, BlockPos pos, Direction fromDir) { return false; }
    }
}
