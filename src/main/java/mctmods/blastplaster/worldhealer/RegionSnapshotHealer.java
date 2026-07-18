package mctmods.blastplaster.worldhealer;

import mctmods.blastplaster.BlastPlaster;
import mctmods.blastplaster.Config;
import mctmods.blastplaster.Config.ExplosionMode;
import mctmods.blastplaster.helper.BlockStatePosWrapper;
import mctmods.blastplaster.util.BlastPlasterUtil;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RegionSnapshotHealer {

    private RegionSnapshotHealer() {}

    private static final int ORPHAN_SCAN_RADIUS = 8;
    private static final int ORPHAN_SWEEP_CAP = 4000;
    private static final int CANOPY_EXTRA_DELAY = 40;
    private static final List<Job> JOBS = new ArrayList<>();

    private static final class Job {
        final ServerLevel level;
        final Map<BlockPos, BlockStatePosWrapper> snapshot;
        final ExplosionMode mode;
        final float visualChance;
        final Set<BlockPos> orphanSeeds = new HashSet<>();
        final Set<BlockPos> dtWoodSeeds = new HashSet<>();
        final Set<BlockPos> deferredPos = new HashSet<>();
        final List<BlockStatePosWrapper> deferred = new ArrayList<>();
        long nextRunGameTime;
        final int interval;
        int remainingPasses;
        int quietPasses;

        Job(ServerLevel level, Map<BlockPos, BlockStatePosWrapper> snapshot, ExplosionMode mode, float visualChance, long nextRunGameTime, int interval, int remainingPasses) {
            this.level = level;
            this.snapshot = snapshot;
            this.mode = mode;
            this.visualChance = visualChance;
            this.nextRunGameTime = nextRunGameTime;
            this.interval = interval;
            this.remainingPasses = remainingPasses;
        }
    }

    public static void scheduleDiffHeal(ServerLevel level, Map<BlockPos, BlockStatePosWrapper> snapshot, int firstDelay, int passInterval, int passes, ExplosionMode mode, float visualChance) {
        if (snapshot.isEmpty() || passes < 1) { return; }
        JOBS.add(new Job(level, snapshot, mode, visualChance, level.getGameTime() + Math.max(1, firstDelay), Math.max(1, passInterval), passes));
        WorldHealerSaveDataSupplier healer = BlastPlaster.getWorldHealer(level);
        if (healer != null) { healer.markDirty(); }
    }

    public static void onLevelTick(ServerLevel level) {
        if (JOBS.isEmpty()) { return; }
        long now = level.getGameTime();
        Iterator<Job> it = JOBS.iterator();
        while (it.hasNext()) {
            Job job = it.next();
            if (job.level != level) { continue; }
            if (now < job.nextRunGameTime) { continue; }
            runDiffPass(job);
            job.remainingPasses--;
            job.nextRunGameTime = now + job.interval;
            WorldHealerSaveDataSupplier healer = BlastPlaster.getWorldHealer(level);
            if (healer != null) { healer.markDirty(); }
            if (job.remainingPasses <= 0 || job.quietPasses >= 5 || (job.snapshot.isEmpty() && job.orphanSeeds.isEmpty())) {
                flushDeferred(job);
                it.remove();
            }
        }
    }

    public static void onLevelUnload(ServerLevel level) { JOBS.removeIf(job -> job.level == level); }

    public static void saveJobs(net.minecraft.world.level.Level rawLevel, CompoundTag tag) {
        if (!(rawLevel instanceof ServerLevel level)) { return; }
        ListTag jobList = new ListTag();
        long now = level.getGameTime();
        for (Job job : JOBS) {
            if (job.level != level) { continue; }
            CompoundTag jobTag = new CompoundTag();
            jobTag.putString("mode", job.mode.name());
            jobTag.putFloat("visualChance", job.visualChance);
            jobTag.putInt("interval", job.interval);
            jobTag.putInt("remainingPasses", job.remainingPasses);
            jobTag.putInt("quietPasses", job.quietPasses);
            jobTag.putLong("delay", Math.max(1L, job.nextRunGameTime - now));

            ListTag snapList = new ListTag();
            for (BlockStatePosWrapper w : job.snapshot.values()) {
                CompoundTag wTag = new CompoundTag();
                w.writeNBT(wTag);
                snapList.add(wTag);
            }
            jobTag.put("snapshot", snapList);

            jobTag.putLongArray("orphanSeeds", toLongList(job.orphanSeeds));
            jobTag.putLongArray("dtWoodSeeds", toLongList(job.dtWoodSeeds));
            jobTag.putLongArray("deferredPos", toLongList(job.deferredPos));

            ListTag deferredList = new ListTag();
            for (BlockStatePosWrapper w : job.deferred) {
                CompoundTag wTag = new CompoundTag();
                w.writeNBT(wTag);
                deferredList.add(wTag);
            }
            jobTag.put("deferredWrappers", deferredList);
            jobList.add(jobTag);
        }
        tag.put("snapshotJobs", jobList);
    }

    public static void loadJobs(net.minecraft.world.level.Level rawLevel, CompoundTag tag) {
        if (!(rawLevel instanceof ServerLevel level) || !tag.contains("snapshotJobs")) { return; }
        ListTag jobList = tag.getList("snapshotJobs", Tag.TAG_COMPOUND);
        long now = level.getGameTime();
        int loaded = 0;
        for (Tag t : jobList) {
            CompoundTag jobTag = (CompoundTag) t;
            ExplosionMode mode;
            try { mode = ExplosionMode.valueOf(jobTag.getString("mode")); }
            catch (IllegalArgumentException e) { mode = ExplosionMode.HEAL; }

            Map<BlockPos, BlockStatePosWrapper> snapshot = new HashMap<>();
            ListTag snapList = jobTag.getList("snapshot", Tag.TAG_COMPOUND);
            for (Tag st : snapList) {
                BlockStatePosWrapper w = new BlockStatePosWrapper();
                w.readNBT((CompoundTag) st, level);
                snapshot.put(w.getPos(), w);
            }

            Job job = new Job(level, snapshot, mode, jobTag.getFloat("visualChance"), now + Math.max(1L, jobTag.getLong("delay")), Math.max(1, jobTag.getInt("interval")), Math.max(1, jobTag.getInt("remainingPasses")));
            job.quietPasses = jobTag.getInt("quietPasses");
            for (long l : jobTag.getLongArray("orphanSeeds")) { job.orphanSeeds.add(BlockPos.of(l)); }
            for (long l : jobTag.getLongArray("dtWoodSeeds")) { job.dtWoodSeeds.add(BlockPos.of(l)); }
            for (long l : jobTag.getLongArray("deferredPos")) { job.deferredPos.add(BlockPos.of(l)); }
            ListTag deferredList = jobTag.getList("deferredWrappers", Tag.TAG_COMPOUND);
            for (Tag dt : deferredList) {
                BlockStatePosWrapper w = new BlockStatePosWrapper();
                w.readNBT((CompoundTag) dt, level);
                job.deferred.add(w);
            }
            JOBS.add(job);
            loaded++;
        }
        if (loaded > 0) { BlastPlaster.LOGGER.info("[BlastPlaster] Restored {} snapshot heal jobs for {}", loaded, level.dimension().location()); }
    }

    private static long[] toLongList(Set<BlockPos> positions) {
        long[] out = new long[positions.size()];
        int i = 0;
        for (BlockPos pos : positions) { out[i++] = pos.asLong(); }
        return out;
    }

    private static void flushDeferred(Job job) {
        if (job.deferred.isEmpty()) { return; }
        WorldHealerSaveDataSupplier healer = BlastPlaster.getWorldHealer(job.level);
        if (healer == null) { BlastPlaster.LOGGER.warn("[BlastPlaster] Deferred flush: no world healer, {} tree/canopy blocks dropped", job.deferred.size()); return; }
        int flushCount = job.deferred.size();
        if (Config.enableDropSuppression()) { BlastPlasterUtil.recordExplosionArea(job.level, job.deferredPos, true); }
        int extra = Math.min(200, Math.max(0, healer.getMaxQueuedDelayTicks() - Config.getMinimumTicksBeforeHeal())) + CANOPY_EXTRA_DELAY;
        healer.prepareAndScheduleHealing(new ArrayList<>(job.deferred), new HashSet<>(job.deferredPos), job.level, extra);
        BlastPlaster.debug("[BlastPlaster] Deferred flush: {} tree/canopy blocks queued at +{} ticks past solid horizon", flushCount, extra);
        job.deferred.clear();
        healer.markDirty();
    }

    private static void runDiffPass(Job job) {
        ServerLevel level = job.level;
        boolean suppressFalling = job.mode == ExplosionMode.HEAL;
        Map<BlockPos, BlockStatePosWrapper> snapshot = job.snapshot;
        List<BlockStatePosWrapper> toHeal = new ArrayList<>();
        Set<BlockPos> affectedPos = new HashSet<>();

        Iterator<Map.Entry<BlockPos, BlockStatePosWrapper>> it = snapshot.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, BlockStatePosWrapper> entry = it.next();
            BlockPos pos = entry.getKey();
            if (!level.isLoaded(pos)) { continue; }
            BlockState current = level.getBlockState(pos);
            if (current == entry.getValue().getState()) { continue; }
            toHeal.add(entry.getValue());
            affectedPos.add(pos);
            it.remove();
        }

        WorldHealerSaveDataSupplier healer = BlastPlaster.getWorldHealer(level);
        if (healer == null) {
            if (!toHeal.isEmpty()) { BlastPlaster.LOGGER.warn("[BlastPlaster] Diff pass: no world healer for {}, {} blocks dropped", level.dimension().location(), toHeal.size()); }
            return;
        }

        int detectedCount = toHeal.size();

        if (Config.healFullTrees()) {
            for (BlockStatePosWrapper w : toHeal) {
                if (BlastPlasterUtil.isDtWood(w.getState())) { job.dtWoodSeeds.add(w.getPos()); }
            }

            int before = toHeal.size();
            healer.addExtraTreeBlocks(toHeal, affectedPos, level);
            if (BlastPlasterUtil.DT_LOADED) {
                int crawled = healer.collectSeveredDynamicWood(toHeal, affectedPos, level, job.dtWoodSeeds);
                if (crawled > 0) { BlastPlaster.debug("[BlastPlaster] Diff pass: cross-pass crawl claimed {} severed DT blocks", crawled); }
            }
            if (toHeal.size() > before) {
                int tornDown = 0;
                for (int i = before; i < toHeal.size(); i++) {
                    BlockStatePosWrapper extra = toHeal.get(i);
                    BlockPos pos = extra.getPos();
                    if (!level.isLoaded(pos)) { continue; }
                    if (level.getBlockState(pos) != extra.getState()) { continue; }
                    snapshot.remove(pos);
                    if (job.mode == ExplosionMode.EJECT_DROPS && BlastPlasterUtil.calculateRealDrop(level)) {
                        BlastPlasterUtil.spawnEjectDrops(level, pos, extra.getState());
                        BlastPlasterUtil.finalizeExplodedBlock(level, pos, extra.getState(), job.mode, true, job.visualChance);
                    } else { BlastPlasterUtil.finalizeExplodedBlock(level, pos, extra.getState(), job.mode, false, job.visualChance); }
                    tornDown++;
                }
                if (tornDown > 0) { BlastPlaster.debug("[BlastPlaster] Diff pass: tree expansion tore down {} extra blocks", tornDown); }
            }

            for (BlockStatePosWrapper w : toHeal) {
                if (w.getState().is(BlockTags.LOGS)) { job.orphanSeeds.add(w.getPos()); }
            }

            int swept = sweepOrphanedLeaves(level, job, toHeal, affectedPos);
            if (swept > 0) { BlastPlaster.debug("[BlastPlaster] Diff pass: orphan sweep tore down {} decaying leaves", swept); }

            if (Config.enableDropSuppression() && !job.orphanSeeds.isEmpty()) { BlastPlasterUtil.recordExplosionArea(level, job.orphanSeeds, suppressFalling); }
        }

        if (toHeal.isEmpty()) {
            job.quietPasses++;
            BlastPlaster.debug("[BlastPlaster] Diff pass: no changed blocks yet, {} still tracked, {} quiet passes", snapshot.size(), job.quietPasses);
            return;
        }
        job.quietPasses = 0;

        if (Config.enableDropSuppression()) { BlastPlasterUtil.recordExplosionArea(level, affectedPos, suppressFalling); }

        if (job.mode != ExplosionMode.HEAL) {
            int visualsAndDrops = 0;
            for (int i = 0; i < detectedCount; i++) {
                BlockStatePosWrapper w = toHeal.get(i);
                BlockPos pos = w.getPos();
                BlockState state = w.getState();
                if (state.getBlock() == net.minecraft.world.level.block.Blocks.TNT) { continue; }
                if (job.mode == ExplosionMode.EJECT_DROPS && BlastPlasterUtil.calculateRealDrop(level)) { BlastPlasterUtil.spawnEjectDrops(level, pos, state); }
                else if (Config.enableFakeTossedBlocks() && level.random.nextFloat() < job.visualChance) { BlastPlasterUtil.spawnVisualTossedBlock(level, pos, state); }
                visualsAndDrops++;
            }
            BlastPlaster.debug("[BlastPlaster] Diff pass ({}): {} destroyed blocks processed, {} remaining tracked", job.mode, visualsAndDrops, snapshot.size());
            return;
        }

        List<BlockStatePosWrapper> canopy = new ArrayList<>();
        for (int i = toHeal.size() - 1; i >= 0; i--) {
            BlockStatePosWrapper w = toHeal.get(i);
            if (isDeferredHealBlock(w.getState())) {
                if (job.deferredPos.add(w.getPos())) {
                    canopy.add(w);
                    job.deferred.add(w);
                }
                toHeal.remove(i);
            }
        }

        if (!toHeal.isEmpty()) { healer.prepareAndScheduleHealing(toHeal, affectedPos, level); }

        BlastPlaster.debug("[BlastPlaster] Diff pass: {} solid blocks queued, {} tree/canopy blocks deferred ({} total held), {} remaining tracked", toHeal.size(), canopy.size(), job.deferred.size(), snapshot.size());
    }

    private static boolean isDeferredHealBlock(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof LeavesBlock || block instanceof VineBlock) { return true; }
        return BlastPlasterUtil.isDynamicTreesAssembly(state);
    }

    private static int sweepOrphanedLeaves(ServerLevel level, Job job, List<BlockStatePosWrapper> toHeal, Set<BlockPos> affectedPos) {
        if (job.orphanSeeds.isEmpty()) { return 0; }

        Set<BlockPos> visited = new HashSet<>();
        Deque<BlockPos> orphanQueue = new ArrayDeque<>();

        for (BlockPos logPos : job.orphanSeeds) {
            if (!level.isLoaded(logPos)) { continue; }
            for (int dx = -ORPHAN_SCAN_RADIUS; dx <= ORPHAN_SCAN_RADIUS; dx++) {
                for (int dy = -ORPHAN_SCAN_RADIUS; dy <= ORPHAN_SCAN_RADIUS; dy++) {
                    for (int dz = -ORPHAN_SCAN_RADIUS; dz <= ORPHAN_SCAN_RADIUS; dz++) {
                        BlockPos adj = logPos.offset(dx, dy, dz);
                        if (!visited.add(adj)) { continue; }
                        if (affectedPos.contains(adj) || job.deferredPos.contains(adj)) { continue; }
                        if (isOrphanLeaf(level.getBlockState(adj))) { orphanQueue.add(adj); }
                    }
                }
            }
        }

        if (orphanQueue.isEmpty()) { return 0; }

        List<BlockPos> orphans = new ArrayList<>();
        while (!orphanQueue.isEmpty() && orphans.size() < ORPHAN_SWEEP_CAP) {
            BlockPos pos = orphanQueue.poll();
            orphans.add(pos);
            for (BlockPos side : BlastPlasterUtil.NEIGHBOR_POSITIONS) {
                BlockPos adj = pos.offset(side);
                if (!visited.add(adj)) { continue; }
                if (affectedPos.contains(adj) || job.deferredPos.contains(adj)) { continue; }
                if (isOrphanLeaf(level.getBlockState(adj))) { orphanQueue.add(adj); }
            }
        }

        int swept = 0;
        for (BlockPos pos : orphans) {
            BlockState state = level.getBlockState(pos);
            if (!isOrphanLeaf(state)) { continue; }
            BlockStatePosWrapper wrapper = new BlockStatePosWrapper(level, pos, state);
            job.snapshot.remove(pos);
            BlastPlasterUtil.finalizeExplodedBlock(level, pos, state, job.mode, false, job.visualChance);
            toHeal.add(wrapper);
            affectedPos.add(pos);
            swept++;
        }
        return swept;
    }

    private static boolean isOrphanLeaf(BlockState state) {
        if (!(state.getBlock() instanceof LeavesBlock)) { return false; }
        if (!state.hasProperty(LeavesBlock.DISTANCE) || !state.hasProperty(LeavesBlock.PERSISTENT)) { return false; }
        if (state.getValue(LeavesBlock.PERSISTENT)) { return false; }
        return state.getValue(LeavesBlock.DISTANCE) >= 7;
    }
}
