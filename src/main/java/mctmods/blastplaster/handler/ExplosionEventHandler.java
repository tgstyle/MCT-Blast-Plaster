package mctmods.blastplaster.handler;

import mctmods.blastplaster.Config;
import mctmods.blastplaster.BlastPlaster;
import mctmods.blastplaster.worldhealer.WorldHealerSaveDataSupplier;
import com.lothrazar.library.events.EventFlib;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.projectile.WitherSkull;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public class ExplosionEventHandler extends EventFlib {

  @SubscribeEvent
  public void onDetonate(ExplosionEvent.Detonate event) {
    if (event.getLevel().isClientSide) return;
    var exploder = event.getExplosion().getDirectSourceEntity();
    boolean isCreeper = exploder instanceof Creeper;
    boolean healThis = false;
    if (Config.healAll()) {
      healThis = true;
    } else {
      if (Config.healCreepers() && isCreeper) healThis = true;
      if (!healThis && Config.healNonPlayerTNT() && exploder instanceof PrimedTnt tnt) {
        LivingEntity owner = tnt.getOwner();
        if (!(owner instanceof Player)) healThis = true;
      }
      if (!healThis && Config.healWither() && (exploder instanceof WitherBoss || exploder instanceof WitherSkull)) {
        healThis = true;
      }
    }
    if (healThis) {
      WorldHealerSaveDataSupplier worldHealer = BlastPlaster.getWorldHealer((ServerLevel) event.getLevel());
      if (worldHealer != null) worldHealer.onDetonate(event);
    }
  }
}
