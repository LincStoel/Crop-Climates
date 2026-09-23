package com.worldtraveler.cropclimates.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.SimpleParticleType;

/**
 * The anti-bone-meal sparkle: a dull, tinted mote that sinks and fades
 * instead of rising green. One provider per cause, each with its own tint.
 */
public class WiltParticle extends TextureSheetParticle {

    protected WiltParticle(ClientLevel level, double x, double y, double z, float r, float g, float b) {
        super(level, x, y, z, 0.0, 0.0, 0.0);
        float shade = 0.85F + random.nextFloat() * 0.15F;
        setColor(r * shade, g * shade, b * shade);
        this.quadSize *= 0.6F + random.nextFloat() * 0.4F;
        this.xd = (random.nextDouble() - 0.5) * 0.01;
        this.zd = (random.nextDouble() - 0.5) * 0.01;
        this.yd = -0.012 - random.nextDouble() * 0.01;
        this.gravity = 0.0F;
        this.hasPhysics = false;
        this.lifetime = 30 + random.nextInt(20);
    }

    @Override
    public void tick() {
        super.tick();
        this.alpha = Math.max(0.0F, 1.0F - (float) age / lifetime);
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    public record Provider(SpriteSet sprites, float r, float g, float b) implements ParticleProvider<SimpleParticleType> {
        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z,
                                       double xSpeed, double ySpeed, double zSpeed) {
            WiltParticle particle = new WiltParticle(level, x, y, z, r, g, b);
            particle.pickSprite(sprites);
            return particle;
        }
    }
}
