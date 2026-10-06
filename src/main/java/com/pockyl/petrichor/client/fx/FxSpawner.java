package com.pockyl.petrichor.client.fx;

import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import com.pockyl.petrichor.ClientConfig;
import com.pockyl.petrichor.client.ClientWeather;
import com.pockyl.petrichor.client.Columns;
import com.pockyl.petrichor.client.render.Puddles;
import com.pockyl.petrichor.client.sound.RainSounds;
import com.pockyl.petrichor.weather.Noise;
import com.pockyl.petrichor.world.SurfaceKind;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides every tick where rain effects appear: splashes on whatever the rain hits (crowns on the ground, rings on
 * water, sizzle on lava, spray off leaves and mobs), mist in downpours, water pouring off edges and dripping from
 * leaves (also for a while after the rain) and splashes under feet in puddles.
 */
public final class FxSpawner {
    private static final int SPLASH_RANGE = 16;
    private static final int DRIP_RANGE = 24;
    private static final int LEAF_RANGE = 14;
    /** In {@code walkDist} units (0.6 per block walked). */
    private static final float STEP_LENGTH = 0.9F;
    private static final int DRIP_POINT_SEED = 0x0D21_0001;

    private final RandomSource random = RandomSource.create();
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private final List<Puddles.Emitter> emitters = new ArrayList<>();
    private final Int2FloatOpenHashMap lastStep = new Int2FloatOpenHashMap();

    public void tick(ClientLevel level, Columns columns, Puddles puddles, RainFx fx, Vec3 cam) {
        fx.clearBeads();
        float intensity = ClientWeather.localIntensity();
        float rain = ClientWeather.localRain();
        float wetness = ClientWeather.wetness();
        if (rain > 0.0F && ClientConfig.SPLASHES.get()) {
            groundSplashes(level, columns, puddles, fx, cam, intensity);
            mist(level, columns, fx, cam, intensity);
            entitySplashes(level, fx, cam, intensity);
        }
        if (ClientConfig.DRIPS.get()) {
            // Edges keep dripping while the ground is wet, long after the rain stopped.
            float after = Math.clamp((wetness - 0.1F) * 0.4F, 0.0F, 0.25F) * (1.0F - Math.min(1.0F, rain * 2.0F));
            edgeDrips(level, puddles, fx, cam, intensity, after);
            leafDrips(level, columns, puddles, fx, cam, intensity, after);
        }
        if (ClientConfig.FOOTSTEPS.get() && wetness > 0.05F) {
            footsteps(level, puddles, fx, cam);
        }
    }

    private static int stochastic(RandomSource random, float expected) {
        int n = (int) expected;
        return random.nextFloat() < expected - n ? n + 1 : n;
    }

    private void groundSplashes(ClientLevel level, Columns columns, Puddles puddles, RainFx fx, Vec3 cam, float intensity) {
        float expected = ClientConfig.quality().splashBudget * (float) (double) ClientConfig.SPLASH_DENSITY.get()
                * ClientWeather.splash / 2.4F * Math.min(intensity, 1.5F);
        int n = stochastic(random, expected);
        float scale = 0.55F + ClientWeather.density * 0.25F;
        for (int s = 0; s < n && !fx.busy(0.55F); s++) {
            float r = 1.0F + SPLASH_RANGE * (float) Math.pow(random.nextFloat(), 0.8);
            float angle = random.nextFloat() * Mth.TWO_PI;
            double x = cam.x + Mth.cos(angle) * r;
            double z = cam.z + Mth.sin(angle) * r;
            int bx = Mth.floor(x);
            int bz = Mth.floor(z);
            if (columns.precipitation(bx, bz) != Columns.RAIN) {
                continue;
            }
            int h = columns.height(bx, bz);
            if (Math.abs(h - cam.y) > 20) {
                continue;
            }
            pos.set(bx, h - 1, bz);
            BlockState state = level.getBlockState(pos);
            SurfaceKind.Shape shape = SurfaceKind.classify(state);
            int light = columns.light(bx, bz);
            switch (shape.kind()) {
                case WATER -> {
                    FluidState fluid = state.getFluidState();
                    double y = h - 1 + fluid.getHeight(level, pos);
                    fx.ripple(x, y, z, scale, light);
                    if (random.nextInt(4) == 0) {
                        fx.splash(x, y, z, scale * 0.6F, RainFx.LAND_WATER, light, 1);
                    }
                }
                case HOT -> {
                    if (random.nextInt(4) == 0) {
                        level.addParticle(ParticleTypes.SMOKE, x, h + 0.05, z, 0.0, 0.03, 0.0);
                    }
                }
                case LEAVES -> {
                    // A drop shatters on the leaves: fine droplets thrown out and down, now and then a breath of spray.
                    int bits = 1 + random.nextInt(3);
                    for (int b = 0; b < bits; b++) {
                        float throwAngle = random.nextFloat() * Mth.TWO_PI;
                        float speed = 0.025F + random.nextFloat() * 0.05F;
                        fx.add(RainFx.DROPLET, x, h + 0.02, z, Mth.cos(throwAngle) * speed, 0.02F + random.nextFloat() * 0.06F,
                                Mth.sin(throwAngle) * speed, 0.55F + random.nextFloat() * 0.4F, 0.6F, 9 + random.nextInt(6), light);
                    }
                    if (random.nextFloat() < 0.08F * ClientWeather.density) {
                        fx.add(RainFx.MIST, x, h + 0.15, z, 0.0F, 0.006F, 0.0F, 0.45F + random.nextFloat() * 0.3F, 0.05F,
                                18 + random.nextInt(10), light);
                    }
                }
                default -> {
                    double y = h - 1 + shape.top();
                    float cover = puddles.coverAt(x, y, z);
                    byte surface = cover > 0.5F ? RainFx.LAND_PUDDLE : RainFx.LAND_GROUND;
                    fx.splash(x, y, z, scale * (surface == RainFx.LAND_PUDDLE ? 0.8F : 1.0F), surface, light, random.nextInt(3));
                }
            }
        }
    }

    private void mist(ClientLevel level, Columns columns, RainFx fx, Vec3 cam, float intensity) {
        float chance = (ClientWeather.density - 1.4F) * 1.5F * Math.min(intensity, 1.5F);
        int n = stochastic(random, chance);
        for (int s = 0; s < n && !fx.full(); s++) {
            float r = 3.0F + random.nextFloat() * 18.0F;
            float angle = random.nextFloat() * Mth.TWO_PI;
            double x = cam.x + Mth.cos(angle) * r;
            double z = cam.z + Mth.sin(angle) * r;
            int bx = Mth.floor(x);
            int bz = Mth.floor(z);
            if (columns.precipitation(bx, bz) != Columns.RAIN) {
                continue;
            }
            int h = columns.height(bx, bz);
            // Heavy rain beating on a forest raises a haze over the crowns: denser and higher there.
            pos.set(bx, h - 1, bz);
            boolean canopy = SurfaceKind.classify(level.getBlockState(pos)).kind() == SurfaceKind.LEAVES;
            float size = canopy ? 1.6F + random.nextFloat() * 1.2F : 1.0F + random.nextFloat() * 0.9F;
            fx.add(RainFx.MIST, x, h + (canopy ? 0.8 : 0.4), z, 0.0F, canopy ? 0.008F : 0.004F, 0.0F, size, canopy ? 0.09F : 0.07F,
                    40 + random.nextInt(20), columns.light(bx, bz));
        }
    }

    private void entitySplashes(ClientLevel level, RainFx fx, Vec3 cam, float intensity) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean firstPerson = minecraft.options.getCameraType().isFirstPerson();
        AABB area = new AABB(cam.x - 12, cam.y - 8, cam.z - 12, cam.x + 12, cam.y + 8, cam.z + 12);
        List<LivingEntity> entities = level.getEntitiesOfClass(LivingEntity.class, area);
        int handled = 0;
        for (LivingEntity entity : entities) {
            if (handled++ > 24 || fx.full()) {
                break;
            }
            if (entity == minecraft.player && firstPerson || entity.isInvisible() || random.nextFloat() > 0.35F * intensity * Math.min(1.0F, ClientWeather.splash)) {
                continue;
            }
            AABB box = entity.getBoundingBox();
            pos.set(entity.getX(), box.maxY, entity.getZ());
            if (!level.isRainingAt(pos)) {
                continue;
            }
            double x = Mth.lerp(random.nextDouble(), box.minX, box.maxX);
            double z = Mth.lerp(random.nextDouble(), box.minZ, box.maxZ);
            int light = LevelRenderer.getLightColor(level, pos);
            fx.splash(x, box.maxY, z, 0.45F, RainFx.LAND_GROUND, light, 1 + random.nextInt(2));
        }
    }

    /**
     * Water off edges: every edge block has two fixed drip points (real water keeps dripping from the same spots). At
     * each a drop swells, hangs and falls in a steady rhythm set by how much water reaches the edge; with a lot of
     * water the rhythm becomes a string of drops. After the rain the points keep dripping slowly.
     */
    private void edgeDrips(ClientLevel level, Puddles puddles, RainFx fx, Vec3 cam, float intensity, float after) {
        if (intensity <= 0.0F && after <= 0.0F) {
            return;
        }
        emitters.clear();
        puddles.emittersNear(cam.x, cam.z, DRIP_RANGE, emitters);
        float density = (float) (double) ClientConfig.DRIP_DENSITY.get();
        long time = level.getGameTime();
        for (Puddles.Emitter emitter : emitters) {
            double dx = emitter.x() - cam.x;
            double dz = emitter.z() - cam.z;
            if (dx * dx + dz * dz > DRIP_RANGE * DRIP_RANGE || Math.abs(emitter.hangY() - cam.y) > 24) {
                continue;
            }
            float flow = (float) Math.pow(emitter.flow(), 0.8);
            float rate = (Math.min(0.5F, 0.03F * flow * intensity) + Math.min(0.025F, 0.004F * flow * after)) * density;
            if (rate < 0.0005F) {
                continue;
            }
            int light = lightAbove(emitter);
            int keyX = Mth.floor(emitter.x() * 4.0F);
            int keyZ = Mth.floor(emitter.z() * 4.0F);
            for (int k = 0; k < 2; k++) {
                float along = (Noise.unit(keyX, keyZ, k, DRIP_POINT_SEED) - 0.5F) * 0.8F;
                float pace = rate * (0.75F + Noise.unit(keyX, keyZ, k + 2, DRIP_POINT_SEED) * 0.5F);
                double offset = Noise.unit(keyX, keyZ, k + 4, DRIP_POINT_SEED);
                double x = emitter.x() - emitter.dirZ() * along;
                double z = emitter.z() + emitter.dirX() * along;
                double before = time * (double) pace + offset;
                double now = (time + 1) * (double) pace + offset;
                float swell = (float) (now - Math.floor(now));
                int falling = (int) (Math.floor(now) - Math.floor(before));
                if (falling > 0 && !fx.busy(0.92F)) {
                    byte surface = emitter.surface();
                    if (surface == RainFx.LAND_GROUND
                            && puddles.coverAt(x + emitter.dirX() * 0.3, emitter.groundY(), z + emitter.dirZ() * 0.3) > 0.5F) {
                        surface = RainFx.LAND_PUDDLE;
                    }
                    fx.addDrip(x, emitter.hangY() - 0.04, z, emitter.dirX() * 0.008F, emitter.dirZ() * 0.008F, emitter.groundY(), surface,
                            1.0F + Noise.unit(keyX, keyZ, k + 6, DRIP_POINT_SEED) * 0.6F, light);
                }
                // The drop hanging at the point: swelling between falls, or a constant bead where water streams.
                float bead = pace > 0.25F ? 0.6F : swell;
                fx.addBead(x, emitter.hangY() - 0.012 - bead * 0.025, z, 0.012F + bead * 0.03F, light);
            }
        }
    }

    private static int lightAbove(Puddles.Emitter emitter) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return 0xF000F0;
        }
        return LevelRenderer.getLightColor(minecraft.level, BlockPos.containing(emitter.x(), emitter.hangY() - 0.5, emitter.z()));
    }

    /**
     * Under trees the rain turns into fewer, bigger drops that gather on the leaves and fall through the crown - most of
     * them along its outer edge (the drip line), few near the trunk - and keep falling for a while after the rain.
     */
    private void leafDrips(ClientLevel level, Columns columns, Puddles puddles, RainFx fx, Vec3 cam, float intensity, float after) {
        float chance = (0.35F * intensity + after) * (float) (double) ClientConfig.DRIP_DENSITY.get();
        if (chance <= 0.0F) {
            return;
        }
        for (int s = 0; s < 24 && !fx.busy(0.7F); s++) {
            double x = cam.x + (random.nextFloat() * 2.0F - 1.0F) * LEAF_RANGE;
            double z = cam.z + (random.nextFloat() * 2.0F - 1.0F) * LEAF_RANGE;
            int bx = Mth.floor(x);
            int bz = Mth.floor(z);
            if (columns.precipitation(bx, bz) != Columns.RAIN) {
                continue;
            }
            int y = columns.height(bx, bz) - 1;
            pos.set(bx, y, bz);
            if (SurfaceKind.classify(level.getBlockState(pos)).kind() != SurfaceKind.LEAVES) {
                continue;
            }
            int open = 0;
            for (int d = 0; d < 4; d++) {
                int nx = bx + (d == 0 ? 1 : d == 1 ? -1 : 0);
                int nz = bz + (d == 2 ? 1 : d == 3 ? -1 : 0);
                pos.set(nx, columns.height(nx, nz) - 1, nz);
                if (SurfaceKind.classify(level.getBlockState(pos)).kind() != SurfaceKind.LEAVES) {
                    open++;
                }
            }
            if (random.nextFloat() > chance * (0.3F + 0.45F * open)) {
                continue;
            }
            // Down through the canopy to its underside.
            int bottom = y;
            while (bottom > y - 10) {
                pos.set(bx, bottom - 1, bz);
                if (SurfaceKind.classify(level.getBlockState(pos)).kind() != SurfaceKind.LEAVES) {
                    break;
                }
                bottom--;
            }
            dropFrom(level, puddles, fx, x, bottom, z);
        }
    }

    /**
     * A drop falling from the underside of the block at {@code blockBottom} down to whatever is below.
     *
     * @return false when the block below is not open air or there is no ground within reach
     */
    public boolean dropFrom(ClientLevel level, Puddles puddles, RainFx fx, double x, int blockBottom, double z) {
        int bx = Mth.floor(x);
        int bz = Mth.floor(z);
        pos.set(bx, blockBottom - 1, bz);
        if (!level.getBlockState(pos).isAir() || fx.busy(0.8F)) {
            return false;
        }
        int floor = blockBottom - 1;
        BlockState below = null;
        while (floor > blockBottom - 32) {
            pos.set(bx, floor, bz);
            below = level.getBlockState(pos);
            if (!below.isAir()) {
                break;
            }
            floor--;
        }
        if (below == null || below.isAir()) {
            return false;
        }
        double groundY;
        byte surface;
        if (below.getFluidState().is(FluidTags.WATER)) {
            groundY = floor + below.getFluidState().getHeight(level, pos);
            surface = RainFx.LAND_WATER;
        } else {
            VoxelShape shape = below.getCollisionShape(level, pos);
            double top = shape.isEmpty() ? 0.0 : shape.max(Direction.Axis.Y);
            groundY = floor + top;
            surface = puddles.coverAt(x, groundY, z) > 0.5F ? RainFx.LAND_PUDDLE : RainFx.LAND_GROUND;
        }
        pos.set(bx, blockBottom - 1, bz);
        fx.addDrip(x, blockBottom - 0.05, z, 0.0F, 0.0F, groundY, surface, 1.2F + random.nextFloat() * 0.6F,
                LevelRenderer.getLightColor(level, pos));
        return true;
    }

    private void footsteps(ClientLevel level, Puddles puddles, RainFx fx, Vec3 cam) {
        AABB area = new AABB(cam.x - 16, cam.y - 8, cam.z - 16, cam.x + 16, cam.y + 8, cam.z + 16);
        List<LivingEntity> entities = level.getEntitiesOfClass(LivingEntity.class, area);
        if (lastStep.size() > 256) {
            lastStep.clear();
        }
        for (LivingEntity entity : entities) {
            if (!entity.onGround() || entity.isSpectator() || entity.isInWater()) {
                continue;
            }
            float walked = entity.walkDist;
            int id = entity.getId();
            if (!lastStep.containsKey(id) || walked < lastStep.get(id)) {
                lastStep.put(id, walked);
                continue;
            }
            if (walked - lastStep.get(id) < STEP_LENGTH) {
                continue;
            }
            lastStep.put(id, walked);
            float cover = puddles.coverAt(entity.getX(), entity.getY(), entity.getZ());
            if (cover < 0.4F) {
                continue;
            }
            pos.set(entity.getX(), entity.getY() + 0.2, entity.getZ());
            int light = LevelRenderer.getLightColor(level, pos);
            float size = Math.min(1.6F, entity.getBbWidth() * 1.6F + 0.4F);
            fx.splash(entity.getX(), entity.getY(), entity.getZ(), size, RainFx.LAND_PUDDLE, light, 3 + random.nextInt(4));
            RainSounds.puddleStep(entity, cover * Math.min(1.0F, size));
        }
    }
}
