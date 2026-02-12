package mctmods.blastplaster;

import mctmods.blastplaster.helper.BlockStatePosWrapper;
import mctmods.blastplaster.worldhealer.WorldHealerSaveDataSupplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@SuppressWarnings("unchecked")
public class AlexsCavesCompat {

    private static final EntityType<?> NUCLEAR_EXPLOSION = ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.fromNamespaceAndPath("alexscaves", "nuclear_explosion"));
    private static final TagKey<Block> NUKE_PROOF = TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("alexscaves", "nuke_proof"));
    private static final Block TREMORZILLA_EGG = ForgeRegistries.BLOCKS.getValue(ResourceLocation.fromNamespaceAndPath("alexscaves", "tremorzilla_egg"));

    private static net.minecraft.network.syncher.EntityDataAccessor<Float> SIZE_ACCESSOR = null;

    private static Method spawnDinosaursMethod = null;

    static {
        try {
            Class<?> explosionClass = Class.forName("com.github.alexmodguy.alexscaves.server.entity.item.NuclearExplosionEntity");

            Field sizeField = explosionClass.getDeclaredField("SIZE");
            sizeField.setAccessible(true);
            SIZE_ACCESSOR = (net.minecraft.network.syncher.EntityDataAccessor<Float>) sizeField.get(null);

            Class<?> eggClass = Class.forName("com.github.alexmodguy.alexscaves.server.block.TremorzillaEggBlock");
            spawnDinosaursMethod = eggClass.getMethod("spawnDinosaurs", Level.class, BlockPos.class, BlockState.class);
        } catch (Exception e) {
            BlastPlaster.LOGGER.warn("Alex's Caves compatibility: Reflection failed for accessors/methods. Using safe fallbacks.", e);
        }
    }

    @SubscribeEvent
    public void onNuclearExplosionSpawn(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide || NUCLEAR_EXPLOSION == null) return;
        Entity entity = event.getEntity();
        if (entity.getType() != NUCLEAR_EXPLOSION) return;

        ServerLevel world = (ServerLevel) event.getLevel();
        if (!world.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) return;

        boolean healThis = Config.healAll() || Config.healNonPlayerTNT();

        if (healThis && Config.healAlexsCavesNukes()) {
            float size = SIZE_ACCESSOR != null ? entity.getEntityData().get(SIZE_ACCESSOR) : 1.75F;
            int chunksAffected = (int) Math.ceil(size);
            int radius = chunksAffected * 15;

            BlockPos center = entity.blockPosition();
            List<BlockStatePosWrapper> toHeal = new ArrayList<>();
            Set<BlockPos> affectedPos = new HashSet<>();

            BlockPos.MutableBlockPos carve = new BlockPos.MutableBlockPos();
            final float fixedWidth = 0.85F;

            Explosion dummyExplosion = new Explosion(world, null, center.getX(), center.getY(), center.getZ(), 10.0F, List.of());

            for (int cx = -chunksAffected; cx <= chunksAffected; cx++) {
                for (int cy = -chunksAffected; cy <= chunksAffected; cy++) {
                    for (int cz = -chunksAffected; cz <= chunksAffected; cz++) {
                        BlockPos chunkCorner = center.offset(cx * 16, cy * 16, cz * 16);
                        for (int x = 0; x < 16; x++) {
                            for (int z = 0; z < 16; z++) {
                                for (int y = 15; y >= 0; y--) {
                                    int worldY = Mth.clamp(chunkCorner.getY() + y, world.getMinBuildHeight(), world.getMaxBuildHeight());
                                    carve.set(chunkCorner.getX() + x, worldY, chunkCorner.getZ() + z);

                                    double absDy = Math.abs(center.getY() - carve.getY());
                                    double yDist = Math.max(0.0, 0.65 - absDy / radius);
                                    double distToCenterSqr = carve.distToLowCornerSqr(center.getX(), carve.getY() - 1.0, center.getZ());
                                    double targetRadiusSqr = yDist * (radius + fixedWidth * radius) * radius;

                                    if (distToCenterSqr <= targetRadiusSqr) {
                                        BlockState state = world.getBlockState(carve);
                                        boolean destroyable = !state.is(NUKE_PROOF) && (state.getBlock().getExplosionResistance(state, world, carve, dummyExplosion) < Config.getNukeMaxResistance()) || state.getBlock() == TREMORZILLA_EGG;
                                        if (destroyable && (!state.isAir() || !state.getFluidState().isEmpty())) {
                                            toHeal.add(new BlockStatePosWrapper(world, carve.immutable(), state));
                                            affectedPos.add(carve.immutable());

                                            if (state.getBlock() == TREMORZILLA_EGG && spawnDinosaursMethod != null) {
                                                try {
                                                    spawnDinosaursMethod.invoke(state.getBlock(), world, carve, state);
                                                } catch (Exception e) {
                                                    BlastPlaster.LOGGER.warn("Failed to invoke spawnDinosaurs for TremorzillaEggBlock.", e);
                                                }
                                            }

                                            state.onBlockExploded(world, carve, dummyExplosion);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (!toHeal.isEmpty()) {
                WorldHealerSaveDataSupplier worldHealer = BlastPlaster.getWorldHealer(world);
                if (worldHealer != null) {
                    worldHealer.prepareAndScheduleHealing(toHeal, affectedPos, world);
                }
            }
        }
    }
}
