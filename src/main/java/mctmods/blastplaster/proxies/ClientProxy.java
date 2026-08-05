package mctmods.blastplaster.proxies;

import mctmods.blastplaster.client.ParticleCampfireSmoke;
import mctmods.blastplaster.client.ParticleFlash;

import net.minecraft.client.Minecraft;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
@SuppressWarnings("unused")
public class ClientProxy extends CommonProxy {

    @Override public void spawnFlash(double x, double y, double z, int count, double spread) {
        Minecraft mc = Minecraft.getMinecraft();
        mc.addScheduledTask(() -> {
            World world = mc.world;
            if (world == null) { return; }
            for (int i = 0; i < count; i++) {
                double px = x + (world.rand.nextDouble() - 0.5) * 2.0 * spread;
                double py = y + (world.rand.nextDouble() - 0.5) * 2.0 * spread;
                double pz = z + (world.rand.nextDouble() - 0.5) * 2.0 * spread;
                mc.effectRenderer.addEffect(new ParticleFlash(world, px, py, pz));
            }
        });
    }

    @Override public void spawnSmoke(double x, double y, double z, int count, double spreadXZ, double spreadY, double speed) {
        Minecraft mc = Minecraft.getMinecraft();
        mc.addScheduledTask(() -> {
            World world = mc.world;
            if (world == null) { return; }
            for (int i = 0; i < count; i++) {
                double px = x + world.rand.nextGaussian() * spreadXZ;
                double py = y + world.rand.nextGaussian() * spreadY;
                double pz = z + world.rand.nextGaussian() * spreadXZ;
                double mx = world.rand.nextGaussian() * speed;
                double my = world.rand.nextGaussian() * speed;
                double mz = world.rand.nextGaussian() * speed;
                mc.effectRenderer.addEffect(new ParticleCampfireSmoke(world, px, py, pz, mx, my, mz));
            }
        });
    }
}
