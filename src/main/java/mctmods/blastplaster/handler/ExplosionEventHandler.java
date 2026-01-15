package mctmods.blastplaster.handler;

import mctmods.blastplaster.BlastPlaster;
import mctmods.blastplaster.Config;
import mctmods.blastplaster.worldhealer.WorldHealerSaveDataSupplier;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.WitherSkull;
import net.minecraft.world.level.Explosion;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public class ExplosionEventHandler {

  public ExplosionEventHandler() {
    MinecraftForge.EVENT_BUS.register(this);
  }

  @SubscribeEvent
  public void onDetonate(ExplosionEvent.Detonate event) {
    if (event.getLevel().isClientSide) return;

    Explosion explosion = event.getExplosion();
    var exploder = explosion.getDirectSourceEntity();

    boolean healThis = Config.healAll();

    if (!healThis) {
      if (Config.healCreepers() && exploder instanceof Creeper) {
        healThis = true;
      }
      if (!healThis && Config.healWither() && (exploder instanceof WitherBoss || exploder instanceof WitherSkull)) {
        healThis = true;
      }
      if (!healThis && Config.healNonPlayerTNT()) {
        LivingEntity indirect = explosion.getIndirectSourceEntity();
        boolean nonPlayerCaused = !(indirect instanceof Player);
        boolean tntInteraction = explosion.blockInteraction == Explosion.BlockInteraction.DESTROY_WITH_DECAY;
        if (nonPlayerCaused && tntInteraction) {
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
