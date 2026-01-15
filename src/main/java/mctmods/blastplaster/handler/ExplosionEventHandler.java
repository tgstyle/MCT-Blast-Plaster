package mctmods.blastplaster.handler;

import mctmods.blastplaster.BlastPlaster;
import mctmods.blastplaster.Config;
import mctmods.blastplaster.worldhealer.WorldHealerSaveDataSupplier;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.WitherSkull;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.Explosion;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

public class ExplosionEventHandler {

  public ExplosionEventHandler() {
    MinecraftForge.EVENT_BUS.register(this);
  }

  @SubscribeEvent
  public void onDetonate(ExplosionEvent.Detonate event) {
    if (event.getLevel().isClientSide) return;

    Explosion explosion = event.getExplosion();
    Entity exploder = explosion.getDirectSourceEntity();
    LivingEntity indirect = explosion.getIndirectSourceEntity();

    boolean healThis = Config.healAll();

    if (!healThis) {
      if (Config.healCreepers() && exploder instanceof Creeper) {
        healThis = true;
      }
      if (!healThis && Config.healWither() && (exploder instanceof WitherBoss || exploder instanceof WitherSkull)) {
        healThis = true;
      }
      if (!healThis && Config.healNonPlayerTNT()) {
        boolean nonPlayerCaused = !(indirect instanceof Player);

        boolean isPrimedTnt = exploder instanceof PrimedTnt;

        boolean isCustomEntity = false;
        for (String idStr : Config.getCustomEntitiesToHeal()) {
          ResourceLocation id = ResourceLocation.tryParse(idStr);
          if (id != null) {
            EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(id);
            if (type != null) {
              if ((exploder != null && exploder.getType() == type) ||
                      (indirect != null && indirect.getType() == type)) {
                isCustomEntity = true;
                break;
              }
            }
          }
        }

        if (nonPlayerCaused && (isPrimedTnt || isCustomEntity)) {
          healThis = true;
        }
      }
    }

    if (healThis) {
      WorldHealerSaveDataSupplier worldHealer = BlastPlaster.getWorldHealer((ServerLevel) event.getLevel());
      if (worldHealer != null) worldHealer.onDetonate(event);
    }
  }
}
