package mctmods.blastplaster.util;

import mctmods.blastplaster.BlastPlaster;
import mctmods.blastplaster.Config;
import mctmods.blastplaster.worldhealer.WorldHealerSaveDataSupplier;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.loot.IGlobalLootModifier;
import net.minecraftforge.common.loot.LootModifier;
import org.jetbrains.annotations.NotNull;

public class KnockOnDropModifier extends LootModifier {

    public static final Codec<KnockOnDropModifier> CODEC = RecordCodecBuilder.create(inst -> codecStart(inst).apply(inst, KnockOnDropModifier::new));

    public KnockOnDropModifier(LootItemCondition[] conditions) { super(conditions); }

    @NotNull @Override protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> generatedLoot, LootContext context) {
        Vec3 origin = context.getParamOrNull(LootContextParams.ORIGIN);
        if (origin == null || !context.hasParam(LootContextParams.BLOCK_STATE)) { return generatedLoot; }
        if (context.hasParam(LootContextParams.THIS_ENTITY) || context.hasParam(LootContextParams.EXPLOSION_RADIUS)) { return generatedLoot; }
        ServerLevel level = context.getLevel();
        if (!Config.view(level).enableDropSuppression()) { return generatedLoot; }
        BlockPos pos = BlockPos.containing(origin);
        boolean outsideBlast = BlastPlasterUtil.outsideBlast(level, pos);
        if (BlastPlasterUtil.knockedLooseByBlast(level, pos)) {
            WorldHealerSaveDataSupplier healer = BlastPlaster.getWorldHealer(level);
            if (outsideBlast && healer != null) { healer.healKnockedLoose(pos, context.getParam(LootContextParams.BLOCK_STATE)); }
            return new ObjectArrayList<>();
        }
        return generatedLoot;
    }

    @Override public Codec<? extends IGlobalLootModifier> codec() { return CODEC; }
}
