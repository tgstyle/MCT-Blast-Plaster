package mctmods.blastplaster.handler;

import mctmods.blastplaster.BlastPlaster;
import mctmods.blastplaster.Config;
import mctmods.blastplaster.Config.ExplosionMode;
import mctmods.blastplaster.helper.BlockStatePosWrapper;
import mctmods.blastplaster.network.NetworkHandler;
import mctmods.blastplaster.util.BlastPlasterUtil;
import mctmods.blastplaster.worldhealer.WorldHealerSaveDataSupplier;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.boss.EntityWither;
import net.minecraft.entity.item.EntityFallingBlock;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityTNTPrimed;
import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityWitherSkull;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Explosion;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.world.ExplosionEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import com.ferreusveritas.dynamictrees.entities.EntityFallingTree;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

public class ExplosionEventHandler {

    private static final Map<Integer, Long2LongOpenHashMap> lastProcessedPositions = new HashMap<>();
    private static final Map<Integer, Long> lastFlashTick = new HashMap<>();
    private static final Map<Integer, Long> lastSweepTick = new HashMap<>();
    private static final Map<Integer, Long2LongOpenHashMap> burningLights = new HashMap<>();
    private static final int FLASH_APART = 2;
    private static final int SWEEP_EVERY = 100;
    private static final List<DelayedTask> DELAYED = new ArrayList<>();
    private static final Field exploderField;

    static {
        exploderField = findExploderField();
        if (exploderField == null) { BlastPlaster.logger.warn("Explosion source field not found, falling back to the placing entity"); }
    }

    public ExplosionEventHandler() { MinecraftForge.EVENT_BUS.register(this); }

    private static Field findExploderField() {
        for (Field field : Explosion.class.getDeclaredFields()) {
            if (!field.getName().equals("exploder") && !field.getName().equals("field_77283_e")) { continue; }
            field.setAccessible(true);
            return field;
        }
        return null;
    }

    private static final class DelayedTask {

        private final WorldServer world;
        private final long runTick;
        private final Runnable action;

        private DelayedTask(WorldServer world, long runTick, Runnable action) {
            this.world = world;
            this.runTick = runTick;
            this.action = action;
        }
    }

    public static void schedule(WorldServer world, int delay, Runnable action) { DELAYED.add(new DelayedTask(world, world.getTotalWorldTime() + Math.max(1, delay), action)); }

    public static void runDelayedTasks(WorldServer world) {
        if (DELAYED.isEmpty()) { return; }
        long now = world.getTotalWorldTime();
        List<DelayedTask> due = null;
        Iterator<DelayedTask> it = DELAYED.iterator();
        while (it.hasNext()) {
            DelayedTask task = it.next();
            if (task.world != world || now < task.runTick) { continue; }
            if (due == null) { due = new ArrayList<>(); }
            due.add(task);
            it.remove();
        }
        if (due == null) { return; }
        for (DelayedTask task : due) { runTask(task); }
    }

    public static void flushWorld(WorldServer world) {
        List<DelayedTask> pending = new ArrayList<>();
        Iterator<DelayedTask> it = DELAYED.iterator();
        while (it.hasNext()) {
            DelayedTask task = it.next();
            if (task.world == world) {
                pending.add(task);
                it.remove();
            }
        }
        for (DelayedTask task : pending) { runTask(task); }
        int dimension = world.provider.getDimension();
        lastProcessedPositions.remove(dimension);
        lastFlashTick.remove(dimension);
        lastSweepTick.remove(dimension);
        burningLights.remove(dimension);
    }

    private static void runTask(DelayedTask task) {
        try { task.action.run(); }
        catch (Exception e) { BlastPlaster.logger.error("BlastPlaster delayed task failed", e); }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST) public void onDetonate(ExplosionEvent.Detonate event) {
        if (event.getWorld().isRemote || !(event.getWorld() instanceof WorldServer)) { return; }

        WorldServer world = (WorldServer) event.getWorld();
        Explosion explosion = event.getExplosion();
        Entity exploder = getExploder(explosion);
        EntityLivingBase indirect = explosion.getExplosivePlacedBy();

        boolean isPlayerIgnitedTNT = false;
        if (exploder instanceof EntityTNTPrimed) {
            EntityLivingBase owner = ((EntityTNTPrimed) exploder).getTntPlacedBy();
            isPlayerIgnitedTNT = (owner instanceof EntityPlayer) || (indirect instanceof EntityPlayer);
        }

        boolean isCreeper = exploder instanceof EntityCreeper;

        boolean processThis = Config.view(world).healAll() || (Config.view(world).processPlayerIgnitedTNT() && isPlayerIgnitedTNT);

        if (!processThis) {
            if (Config.view(world).healCreepers() && isCreeper) { processThis = true; }
            if (!processThis && Config.view(world).healWither() && (exploder instanceof EntityWither || exploder instanceof EntityWitherSkull)) { processThis = true; }
            if (!processThis && Config.view(world).healNonPlayerTNT()) {
                boolean nonPlayerCaused = !(indirect instanceof EntityPlayer);
                boolean isPrimedTnt = exploder instanceof EntityTNTPrimed;
                boolean isCustomEntity = isCustomEntity(exploder, indirect);
                if (nonPlayerCaused && (isPrimedTnt || isCustomEntity)) { processThis = true; }
            }
        }

        BlastPlaster.debug("Detonate: at {} {} {}, exploder {}, indirect {}, creeper {}, playerTNT {}, process {}, dtLoaded {}, affected {}", (int) explosion.getPosition().x, (int) explosion.getPosition().y, (int) explosion.getPosition().z, exploder == null ? "none" : exploder.getClass().getSimpleName(), indirect == null ? "none" : indirect.getClass().getSimpleName(), isCreeper, isPlayerIgnitedTNT, processThis, BlastPlasterUtil.DT_LOADED, event.getAffectedBlocks().size());

        if (!processThis) { return; }

        Vec3d explosionCenter = explosion.getPosition();
        final long currentTick = world.getTotalWorldTime();
        Long2LongOpenHashMap processed = lastProcessedPositions.computeIfAbsent(world.provider.getDimension(), k -> newProcessedMap());
        Long lastSweep = lastSweepTick.get(world.provider.getDimension());
        if (lastSweep == null || currentTick - lastSweep >= SWEEP_EVERY) {
            processed.long2LongEntrySet().removeIf(e -> currentTick - e.getLongValue() > 600L);
            lastSweepTick.put(world.provider.getDimension(), currentTick);
        }

        if (Config.view(world).enableExplosionFlash()) { spawnImmediateExplosionVisuals(world, explosionCenter); }
        if (Config.view(world).enableExplosionSmoke()) { spawnExplosionSmoke(world, explosionCenter); }

        ExplosionMode mode = Config.view(world).getExplosionMode();
        ExplosionMode effectiveMode = (isPlayerIgnitedTNT && Config.view(world).playerTNTAlwaysDrops()) ? ExplosionMode.EJECT_DROPS : mode;
        if (Config.view(world).healAll()) { effectiveMode = ExplosionMode.HEAL; }
        WorldHealerSaveDataSupplier worldHealer = (effectiveMode == ExplosionMode.HEAL) ? BlastPlaster.getWorldHealer(world) : null;

        boolean forcePlayerTNTDrops = isPlayerIgnitedTNT && Config.view(world).playerTNTAlwaysDrops();

        List<BlockStatePosWrapper> toProcess = new ArrayList<>();
        Set<BlockPos> affectedPos = new HashSet<>();

        int dtBlocks = 0;
        for (BlockPos pos : event.getAffectedBlocks()) {
            IBlockState state = world.getBlockState(pos);
            if (state.getBlock().isAir(state, world, pos)) { continue; }
            if (BlastPlasterUtil.isDynamicTreesAssembly(state)) { dtBlocks++; }
            toProcess.add(new BlockStatePosWrapper(world, pos, state));
            affectedPos.add(pos);
        }

        BlastPlaster.debug("Detonate: collected {} blocks ({} dynamic tree), mode {}, healer {}", toProcess.size(), dtBlocks, effectiveMode, worldHealer == null ? "none" : "present");

        if (toProcess.isEmpty()) { return; }

        if (Config.view(world).healFullTrees()) {
            WorldHealerSaveDataSupplier expansionHealer = BlastPlaster.getWorldHealer(world);
            if (expansionHealer == null) { BlastPlaster.debug("Detonate: tree expansion skipped, no world healer"); }
            else {
                int beforeExpansion = toProcess.size();
                expansionHealer.addExtraTreeBlocks(toProcess, affectedPos, world);
                BlastPlaster.debug("Detonate: tree expansion added {} blocks", toProcess.size() - beforeExpansion);
            }
        }

        if (effectiveMode != ExplosionMode.EJECT_DROPS) {
            BlastPlasterUtil.addAttachedCocoaPods(toProcess, affectedPos, world);
            BlastPlasterUtil.addReedVerticals(toProcess, affectedPos, world);
            if (Config.view(world).enableDropSuppression()) { BlastPlasterUtil.recordExplosionArea(world, affectedPos, effectiveMode == ExplosionMode.HEAL); }
        }

        event.getAffectedBlocks().removeAll(affectedPos);

        List<BlockStatePosWrapper> toClear = toProcess;
        if (worldHealer != null) { toClear = worldHealer.prepareAndScheduleHealing(toProcess, affectedPos, world); }

        List<BlastPlasterUtil.PendingDrop> pendingRealDrops = new ArrayList<>();

        if (effectiveMode == ExplosionMode.EJECT_DROPS && (forcePlayerTNTDrops || isCreeper)) {
            for (BlockStatePosWrapper wrapper : toClear) {
                BlockPos pos = wrapper.getPos();
                IBlockState state = wrapper.getState();
                if (state.getBlock() == Blocks.TNT) { continue; }
                if (BlastPlasterUtil.isDynamicTrees(state) && Config.view(world).dtSpecialDrops()) { BlastPlasterUtil.addDynamicTreesDropsToPending(pendingRealDrops, world, pos, state, forcePlayerTNTDrops); }
                else if (forcePlayerTNTDrops && Config.view(world).playerTNTDropFullBlocks()) { pendingRealDrops.add(new BlastPlasterUtil.PendingDrop(centerOf(pos), new ItemStack(state.getBlock(), 1, state.getBlock().damageDropped(state)), true)); }
                else { addBlockDrops(pendingRealDrops, world, pos, state, forcePlayerTNTDrops); }
            }
        }

        int cleared = 0;
        BlastPlasterUtil.setDtDestroyIgnored(true);
        try {
            for (BlockStatePosWrapper wrapper : toClear) {
                BlockPos pos = wrapper.getPos();
                long posKey = pos.toLong();
                if (currentTick - processed.get(posKey) < 5) { continue; }
                processed.put(posKey, currentTick);
                cleared++;

                IBlockState state = wrapper.getState();

                if (effectiveMode == ExplosionMode.EJECT_DROPS && !(forcePlayerTNTDrops || isCreeper)) {
                    if (state.getBlock() == Blocks.TNT) { continue; }
                    if (BlastPlasterUtil.isDynamicTrees(state) && Config.view(world).dtSpecialDrops()) { BlastPlasterUtil.addDynamicTreesDropsToPending(pendingRealDrops, world, pos, state, false); }
                    else { addBlockDrops(pendingRealDrops, world, pos, state, false); }
                }

                BlastPlasterUtil.finalizeExplodedBlock(world, pos, state, effectiveMode, false, BlastPlasterUtil.getVisualSpawnChance(isCreeper));
            }
        }
        finally { BlastPlasterUtil.setDtDestroyIgnored(false); }

        if (!pendingRealDrops.isEmpty()) {
            schedule(world, 2, () -> {
                for (BlastPlasterUtil.PendingDrop p : pendingRealDrops) {
                    EntityItem item = new EntityItem(world, p.pos().x, p.pos().y + 0.5, p.pos().z, p.stack());
                    BlastPlasterUtil.markSuppressionBypass(item);
                    if (p.isGentle()) { BlastPlasterUtil.applyGentleTossVelocity(item, world); }
                    else { BlastPlasterUtil.applyTossVelocity(item, world); }
                    world.spawnEntity(item);
                }
            });
        }

        BlastPlaster.debug("Detonate: cleared {} of {} blocks, {} pending drops", cleared, toClear.size(), pendingRealDrops.size());

        if (Config.view(world).enableExplosionFlash()) { placeTemporaryLight(world, new BlockPos(explosionCenter), Config.view(world).getExplosionFlashLightLevel(), Config.view(world).getExplosionFlashDuration()); }
    }

    @SubscribeEvent public void onEntityJoinWorld(EntityJoinWorldEvent event) {
        if (event.getWorld().isRemote) { return; }
        Entity entity = event.getEntity();

        if (entity instanceof EntityItem) {
            if (BlastPlasterUtil.shouldSuppressItemDrop((EntityItem) entity)) { event.setCanceled(true); }
            return;
        }

        if (entity instanceof EntityFallingBlock) {
            if (BlastPlasterUtil.shouldSuppressFallingBlock((EntityFallingBlock) entity)) { event.setCanceled(true); }
            return;
        }

        if (BlastPlasterUtil.DT_LOADED && entity instanceof EntityFallingTree && event.getWorld() instanceof WorldServer) {
            if (BlastPlasterUtil.shouldSuppressLaunchAt((WorldServer) event.getWorld(), entity.getPositionVector())) { event.setCanceled(true); }
        }
    }

    @SubscribeEvent public void onLivingDrops(LivingDropsEvent event) {
        if (!event.getSource().isExplosion()) { return; }
        if (Config.view(event.getEntity().world).preventMobDrops()) {
            event.setCanceled(true);
            return;
        }
        for (EntityItem item : event.getDrops()) { item.getEntityData().setBoolean("BlastPlasterMobDrop", true); }
    }

    private Entity getExploder(Explosion explosion) {
        if (exploderField != null) {
            try { return (Entity) exploderField.get(explosion); }
            catch (Exception e) { BlastPlaster.debug("Explosion source lookup failed: {}", String.valueOf(e)); }
        }
        return explosion.getExplosivePlacedBy();
    }

    private boolean isCustomEntity(Entity exploder, EntityLivingBase indirect) {
        World source = exploder != null ? exploder.world : indirect != null ? indirect.world : null;
        for (String idStr : Config.view(source).getCustomEntitiesToHeal()) {
            if (idStr == null || idStr.trim().isEmpty()) { continue; }
            ResourceLocation id = new ResourceLocation(idStr.trim());
            if (exploder != null && id.equals(EntityList.getKey(exploder))) { return true; }
            if (indirect != null && id.equals(EntityList.getKey(indirect))) { return true; }
        }
        return false;
    }

    private void addBlockDrops(List<BlastPlasterUtil.PendingDrop> pending, WorldServer world, BlockPos pos, IBlockState state, boolean gentle) {
        NonNullList<ItemStack> drops = NonNullList.create();
        state.getBlock().getDrops(drops, world, pos, state, 0);
        Vec3d center = centerOf(pos);
        for (ItemStack stack : drops) {
            if (!stack.isEmpty()) { pending.add(new BlastPlasterUtil.PendingDrop(center, stack, gentle)); }
        }
    }

    private static Vec3d centerOf(BlockPos pos) { return new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5); }

    private static void spawnExplosionSmoke(WorldServer world, Vec3d center) {
        int duration = Config.view(world).getExplosionSmokeDuration();
        int particleCount = Config.view(world).getExplosionSmokeParticleCount();
        int burstInterval = 15;
        int numBursts = Math.max(1, duration / burstInterval);

        for (int i = 0; i < numBursts; i++) {
            final int smokeCount = (i == 0) ? particleCount * 2 : particleCount;
            final double yOffset = 0.25 + (i * 0.06);
            schedule(world, i * burstInterval, () -> NetworkHandler.sendSmoke(world, center.x, center.y + yOffset, center.z, smokeCount, 1.2, 0.7, 0.04));
        }
    }

    private static Long2LongOpenHashMap newProcessedMap() {
        Long2LongOpenHashMap map = new Long2LongOpenHashMap();
        map.defaultReturnValue(-1000L);
        return map;
    }

    private static void spawnImmediateExplosionVisuals(WorldServer world, Vec3d center) {
        int dimension = world.provider.getDimension();
        long currentTick = world.getTotalWorldTime();
        if (currentTick - lastFlashTick.getOrDefault(dimension, -1000L) < 2) { return; }
        lastFlashTick.put(dimension, currentTick);

        int flashCount = Config.view(world).getExplosionFlashParticleCount();
        int pulses = Config.view(world).getExplosionFlashPulses();

        for (int i = 0; i < pulses; i++) {
            final int count = (int) (flashCount * (1.0 - 0.25 * i));
            final double spread = 0.6 + i * 0.4;
            final double yBase = center.y + 0.5 + (i * 0.15);
            final int pulseIndex = i;

            schedule(world, i * 3, () -> {
                NetworkHandler.sendFlash(world, center.x, yBase, center.z, count, spread);
                if (pulseIndex == 0) {
                    world.spawnParticle(EnumParticleTypes.EXPLOSION_HUGE, true, center.x, center.y, center.z, 6, 0.0, 0.0, 0.0, 0.0);
                    world.spawnParticle(EnumParticleTypes.EXPLOSION_LARGE, true, center.x, center.y + 0.3, center.z, 45, 0.0, 1.0, 1.0, 0.0);
                }
                else { world.spawnParticle(EnumParticleTypes.EXPLOSION_LARGE, true, center.x, center.y + 0.3, center.z, 25, 0.6, 0.6, 0.6, 0.0); }
            });
        }
    }

    private static void placeTemporaryLight(WorldServer world, BlockPos center, int lightLevel, int duration) {
        if (lightLevel <= 0 || duration < 1) { return; }
        BlockPos spot = airSpot(world, center);
        if (spot == null) { return; }
        long now = world.getTotalWorldTime();
        Long2LongOpenHashMap burning = burningLights.computeIfAbsent(world.provider.getDimension(), k -> new Long2LongOpenHashMap());
        if (flashNear(burning, spot, now)) { return; }
        long key = spot.toLong();
        burning.put(key, now + duration);
        world.setBlockState(spot, BlastPlaster.LIGHT.getDefaultState(), 2);
        schedule(world, duration, () -> {
            burning.remove(key);
            if (world.getBlockState(spot).getBlock() == BlastPlaster.LIGHT) { world.setBlockState(spot, Blocks.AIR.getDefaultState(), 2); }
        });
    }

    @Nullable private static BlockPos airSpot(WorldServer world, BlockPos center) {
        if (world.isAirBlock(center)) { return center; }
        for (EnumFacing side : EnumFacing.values()) {
            BlockPos beside = center.offset(side);
            if (world.isAirBlock(beside)) { return beside; }
        }
        return null;
    }

    private static boolean flashNear(Long2LongOpenHashMap burning, BlockPos spot, long now) {
        Iterator<Long2LongMap.Entry> it = burning.long2LongEntrySet().iterator();
        while (it.hasNext()) {
            Long2LongMap.Entry held = it.next();
            if (held.getLongValue() <= now) {
                it.remove();
                continue;
            }
            BlockPos at = BlockPos.fromLong(held.getLongKey());
            if (Math.abs(at.getX() - spot.getX()) <= FLASH_APART && Math.abs(at.getY() - spot.getY()) <= FLASH_APART && Math.abs(at.getZ() - spot.getZ()) <= FLASH_APART) { return true; }
        }
        return false;
    }
}
