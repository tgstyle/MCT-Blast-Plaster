package mctmods.blastplaster.client;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nonnull;

@SideOnly(Side.CLIENT)
public class ParticleFlash extends Particle {

    public ParticleFlash(World world, double x, double y, double z) {
        super(world, x, y, z);
        this.particleMaxAge = 4;
        this.particleGravity = 0.0F;
        this.canCollide = false;
        this.motionX = 0.0D;
        this.motionY = 0.0D;
        this.motionZ = 0.0D;
        this.particleRed = 1.0F;
        this.particleGreen = 1.0F;
        this.particleBlue = 1.0F;
        this.setParticleTextureIndex(0);
    }

    @Override public void onUpdate() {
        this.prevPosX = this.posX;
        this.prevPosY = this.posY;
        this.prevPosZ = this.posZ;
        if (this.particleAge++ >= this.particleMaxAge) { this.setExpired(); }
    }

    @Override public void renderParticle(@Nonnull BufferBuilder buffer, @Nonnull Entity entity, float partialTicks, float rotationX, float rotationZ, float rotationYZ, float rotationXY, float rotationXZ) {
        float progress = (float) this.particleAge + partialTicks - 1.0F;
        this.particleAlpha = 0.6F - progress * 0.25F * 0.5F;
        this.particleScale = 71.0F * MathHelper.sin(progress * 0.25F * (float) Math.PI);
        super.renderParticle(buffer, entity, partialTicks, rotationX, rotationZ, rotationYZ, rotationXY, rotationXZ);
    }

    @Override public int getBrightnessForRender(float partialTicks) { return 15728880; }
}
