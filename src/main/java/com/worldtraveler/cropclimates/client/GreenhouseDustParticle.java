package com.worldtraveler.cropclimates.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;

/**
 * A sandy mote kicked up off a dry greenhouse's floor: it fades in, rises
 * slowly with a lazy sideways wander, and fades out a block or two up.
 */
public class GreenhouseDustParticle extends TextureSheetParticle {

    private final float wanderPhase;

    protected GreenhouseDustParticle(ClientLevel level, double x, double y, double z) {
        super(level, x, y, z, 0.0, 0.0, 0.0);
        float shade = 0.8F + random.nextFloat() * 0.2F;
        setColor(0.86F * shade, 0.76F * shade, 0.56F * shade);
        this.quadSize *= 0.5F + random.nextFloat() * 0.4F;
        this.xd = (random.nextDouble() - 0.5) * 0.004;
        this.zd = (random.nextDouble() - 0.5) * 0.004;
        this.yd = 0.008 + random.nextDouble() * 0.008;
        this.gravity = 0.0F;
        this.friction = 1.0F;
        this.hasPhysics = true;
        this.lifetime = 100 + random.nextInt(60);
        this.wanderPhase = random.nextFloat() * Mth.TWO_PI;
        this.alpha = 0.0F;
    }

    @Override
    public void tick() {
        super.tick();
        float t = (float) age / lifetime;
        // Fade in over the first fifth, out over the last half.
        this.alpha = 0.8F * Math.min(Math.min(1.0F, t * 5.0F), Math.min(1.0F, (1.0F - t) * 2.0F));
        float wander = wanderPhase + age * 0.08F;
        this.xd += Mth.cos(wander) * 0.0004;
        this.zd += Mth.sin(wander) * 0.0004;
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    public record Provider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z,
                                       double xSpeed, double ySpeed, double zSpeed) {
            GreenhouseDustParticle particle = new GreenhouseDustParticle(level, x, y, z);
            particle.pickSprite(sprites);
            return particle;
        }
    }
}
