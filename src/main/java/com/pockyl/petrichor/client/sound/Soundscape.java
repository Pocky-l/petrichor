package com.pockyl.petrichor.client.sound;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import com.pockyl.petrichor.ClientConfig;
import com.pockyl.petrichor.client.ClientWeather;
import com.pockyl.petrichor.client.Columns;
import com.pockyl.petrichor.client.render.Puddles;
import com.pockyl.petrichor.world.SoundMaterial;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The rain as heard from where the listener stands, rebuilt from the world around them a few times a second.
 *
 * <p>Layers, as in the rain systems of big games:
 * <ul>
 *   <li><b>bed</b> - the hush of rain on the open ground, one source per direction, sitting on the nearest ground the
 *   rain reaches; it crossfades between light, medium and heavy recordings with the rain's intensity;</li>
 *   <li><b>surfaces</b> - stone, planks, metal, glass, wool, water and puddles near the listener each get their own
 *   sources with recordings of rain on that very material, light and heavy;</li>
 *   <li><b>shelter</b> - the roof right above (tin drums, planks knock, a skylight taps, a tent thuds, a thick roof
 *   rumbles), windows struck by the wind-driven rain, tree crowns;</li>
 *   <li><b>space</b> - rain far away in every open direction and the wind, which make the world feel big outside and
 *   fade to a muffled murmur inside.</li>
 * </ul>
 * Everything between the listener and a source muffles it (a low-pass filter, see {@link Muffler}): through glass or
 * leaves a little, through a wall a lot. The scan is spread over ticks and its results glide, so nothing jumps.
 */
final class Soundscape {
    private static final int RADIUS = 12;
    private static final int SECTORS = 6;
    private static final int ACCENT_SECTORS = 3;
    private static final int CANOPY_SOURCES = 3;
    private static final int CANOPY_RANGE = 10;
    private static final int WINDOW_SOURCES = 2;
    private static final int FAR_SOURCES = 4;
    private static final int SCAN_INTERVAL = 5;

    private static final float GROUND_GAIN = 0.3F;
    private static final float LEAF_GAIN = 0.32F;
    private static final float ROOF_GAIN = 0.5F;
    private static final float WINDOW_GAIN = 0.42F;
    private static final float FAR_GAIN = 0.2F;
    private static final float WIND_GAIN = 0.26F;
    /** Surfaces with their own sources, and how loud rain on each is. */
    private static final SoundMaterial[] ACCENTS = {SoundMaterial.HARD, SoundMaterial.WOOD, SoundMaterial.METAL, SoundMaterial.GLASS,
            SoundMaterial.FABRIC, SoundMaterial.WATER, SoundMaterial.PUDDLE};
    private static final float[] ACCENT_GAIN = {0.3F, 0.42F, 0.4F, 0.34F, 0.34F, 0.45F, 0.3F};
    /** Weighted area (see {@link #weight}) of a surface that makes its source full: about five blocks close by. */
    private static final float ACCENT_FULL = 3.0F;

    /** What lies between the listener and a source: volume factor and highs. */
    private record Occlusion(float volume, float highs) {
    }

    private static final Occlusion CLEAR = new Occlusion(1.0F, 1.0F);
    private static final Occlusion THIN = new Occlusion(0.7F, 0.35F);
    private static final Occlusion SOLID = new Occlusion(0.45F, 0.08F);
    private static final Occlusion DEEP = new Occlusion(0.3F, 0.03F);

    private enum Roof {
        NONE, METAL, WOOD, GLASS, FABRIC, THICK
    }

    private final Voice[] bed = new Voice[SECTORS];
    private final Voice[][] accents = new Voice[ACCENTS.length][ACCENT_SECTORS];
    private final Voice[] canopy = new Voice[CANOPY_SOURCES];
    private final Voice[] windows = new Voice[WINDOW_SOURCES];
    private final Voice[] far = new Voice[FAR_SOURCES];
    private final Voice overhead = new Voice(2, false);
    private final Voice wind = new Voice(1, true);
    private final float[] windowHit = new float[WINDOW_SOURCES];
    private Roof roof = Roof.NONE;
    private float enclosure;
    private int ticks;

    // Scan scratch.
    private final float[] bedWeight = new float[SECTORS];
    private final float[] bedReference = new float[SECTORS];
    private final double[][] bedNear = new double[SECTORS][4];
    private final double[][] bedCentroid = new double[SECTORS][4];
    private final float[][] accentWeight = new float[ACCENTS.length][ACCENT_SECTORS];
    private final double[][][] accentNear = new double[ACCENTS.length][ACCENT_SECTORS][4];
    private final double[][][] accentCentroid = new double[ACCENTS.length][ACCENT_SECTORS][4];
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    Soundscape() {
        for (int i = 0; i < SECTORS; i++) {
            bed[i] = new Voice(3, false);
        }
        for (Voice[] row : accents) {
            for (int i = 0; i < ACCENT_SECTORS; i++) {
                row[i] = new Voice(2, false);
            }
        }
        for (int i = 0; i < CANOPY_SOURCES; i++) {
            canopy[i] = new Voice(1, false);
        }
        for (int i = 0; i < WINDOW_SOURCES; i++) {
            windows[i] = new Voice(1, false);
        }
        for (int i = 0; i < FAR_SOURCES; i++) {
            far[i] = new Voice(1, true);
        }
    }

    /** 0 out in the open .. 1 in a closed room under a roof. */
    float enclosure() {
        return enclosure;
    }

    void tick(ClientLevel level, Columns columns, Puddles puddles, Vec3 eye) {
        int phase = ticks++ % SCAN_INTERVAL;
        // The scan is spread over the interval so no tick does all of it.
        switch (phase) {
            case 0 -> scanGround(level, columns, puddles, eye);
            case 1 -> {
                scanCanopy(level, columns, eye);
                scanRoof(level, columns, eye);
            }
            case 2 -> {
                scanEnclosure(level, columns, eye);
                scanFar(level, columns, eye);
            }
            default -> {
                if (ticks % 20 == 4) {
                    scanWindows(level, columns, eye);
                }
            }
        }
        play(eye);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Mixing
    // ------------------------------------------------------------------------------------------------------------

    private void play(Vec3 eye) {
        float rain = ClientWeather.rain();
        float s = RainSounds.intensity();
        float volume = (float) (double) ClientConfig.RAIN_VOLUME.get();
        float loud = rain * RainSounds.loudness(s) * volume;
        // Bed: light, medium and heavy rain, two at a time in an equal-power crossfade.
        float light;
        float medium = 0.0F;
        float heavy = 0.0F;
        if (s <= 0.15F) {
            light = 1.0F;
        } else if (s <= 0.5F) {
            float t = (s - 0.15F) / 0.35F * Mth.HALF_PI;
            light = Mth.cos(t);
            medium = Mth.sin(t);
        } else if (s <= 0.95F) {
            float t = (s - 0.5F) / 0.45F * Mth.HALF_PI;
            light = 0.0F;
            medium = Mth.cos(t);
            heavy = Mth.sin(t);
        } else {
            light = 0.0F;
            heavy = 1.0F;
        }
        for (Voice voice : bed) {
            float base = voice.amount * voice.occlusion * loud * GROUND_GAIN;
            voice.drive(0, PetrichorSounds.GROUND_LIGHT, base * light);
            voice.drive(1, PetrichorSounds.GROUND_MEDIUM, base * medium);
            voice.drive(2, PetrichorSounds.GROUND_HEAVY, base * heavy);
        }

        // Surfaces: a light and a heavy recording of each, crossfading over a wider range.
        float mix = Mth.clamp((s - 0.25F) / 0.6F, 0.0F, 1.0F) * Mth.HALF_PI;
        float lightShare = Mth.cos(mix);
        float heavyShare = Mth.sin(mix);
        for (int m = 0; m < ACCENTS.length; m++) {
            SoundEvent[] pair = ACCENT_SOUNDS[m];
            for (Voice voice : accents[m]) {
                float base = voice.amount * voice.occlusion * loud * ACCENT_GAIN[m];
                drivePair(voice, pair, base, lightShare, heavyShare);
            }
        }

        // Leaves: a drizzle only whispers in the crowns, a downpour roars.
        float leafLoud = loud * Math.min(1.0F, 0.25F + s * 0.85F);
        for (Voice voice : canopy) {
            voice.drive(0, PetrichorSounds.LEAVES, voice.amount * voice.occlusion * leafLoud * LEAF_GAIN);
        }

        SoundEvent[] roofPair = roofSounds(roof);
        if (roofPair == null || !ClientConfig.ROOF.get()) {
            overhead.silence();
        } else {
            float base = overhead.amount * loud * ROOF_GAIN * (roof == Roof.THICK ? 0.8F : 1.0F);
            drivePair(overhead, roofPair, base, lightShare, heavyShare);
        }

        for (int i = 0; i < WINDOW_SOURCES; i++) {
            Voice voice = windows[i];
            voice.drive(0, PetrichorSounds.WINDOW, voice.amount * voice.occlusion * loud * WINDOW_GAIN * windowHit[i]
                    * (0.45F + 0.55F * Math.min(1.0F, s)));
        }

        for (Voice voice : far) {
            voice.drive(0, PetrichorSounds.FAR, voice.amount * voice.occlusion * loud * FAR_GAIN * (0.5F + 0.5F * Math.min(1.0F, s)));
        }

        playWind(eye, rain);
    }

    /** A light and a heavy recording crossfading; a surface with a single recording swells with the intensity instead. */
    private static void drivePair(Voice voice, SoundEvent[] pair, float base, float lightShare, float heavyShare) {
        if (pair[0] == pair[1]) {
            voice.drive(0, pair[0], base * (0.45F + 0.55F * heavyShare));
            voice.drive(1, null, 0.0F);
        } else {
            voice.drive(0, pair[0], base * lightShare);
            voice.drive(1, pair[1], base * heavyShare);
        }
    }

    private void playWind(Vec3 eye, float rain) {
        float wx = ClientWeather.windX();
        float wz = ClientWeather.windZ();
        float speed = Mth.sqrt(wx * wx + wz * wz);
        float strength = (float) Math.pow(Mth.clamp((speed - 0.05F) / 0.25F, 0.0F, 1.0F), 1.2) * rain;
        if (strength <= 0.0F) {
            wind.silence();
            return;
        }
        // The wind comes from upwind; indoors it is a muffled moan outside.
        double ux = speed > 0.0F ? -wx / speed : 1.0;
        double uz = speed > 0.0F ? -wz / speed : 0.0;
        wind.place(eye.x + ux * 10.0, eye.y + 2.0, eye.z + uz * 10.0, strength);
        wind.highs = 1.0F - enclosure * 0.85F;
        float gust = (float) Math.sqrt(Math.max(0.25F, RainSounds.gust()));
        float windVolume = (float) (double) ClientConfig.WIND_VOLUME.get();
        wind.drive(0, PetrichorSounds.WIND, strength * gust * WIND_GAIN * windVolume * (1.0F - enclosure * 0.55F));
    }

    /** The light and heavy recording of each surface in {@link #ACCENTS}. */
    private static final SoundEvent[][] ACCENT_SOUNDS = {
            {PetrichorSounds.HARD_LIGHT, PetrichorSounds.HARD_HEAVY},
            {PetrichorSounds.WOOD_LIGHT, PetrichorSounds.WOOD_HEAVY},
            {PetrichorSounds.METAL_LIGHT, PetrichorSounds.METAL_HEAVY},
            {PetrichorSounds.ROOF_GLASS_LIGHT, PetrichorSounds.ROOF_GLASS_HEAVY},
            {PetrichorSounds.ROOF_FABRIC_LIGHT, PetrichorSounds.ROOF_FABRIC_HEAVY},
            {PetrichorSounds.WATER_LIGHT, PetrichorSounds.WATER_HEAVY},
            {PetrichorSounds.PUDDLE, PetrichorSounds.PUDDLE}};

    private static SoundEvent[] roofSounds(Roof roof) {
        return switch (roof) {
            case METAL -> ROOF_METAL;
            case WOOD -> ROOF_WOOD;
            case GLASS -> ACCENT_SOUNDS[3];
            case FABRIC -> ACCENT_SOUNDS[4];
            case THICK -> ROOF_THICK;
            default -> null;
        };
    }

    private static final SoundEvent[] ROOF_METAL = {PetrichorSounds.ROOF_METAL_LIGHT, PetrichorSounds.ROOF_METAL_HEAVY};
    private static final SoundEvent[] ROOF_WOOD = {PetrichorSounds.ROOF_WOOD_LIGHT, PetrichorSounds.ROOF_WOOD_HEAVY};
    private static final SoundEvent[] ROOF_THICK = {PetrichorSounds.ROOF_THICK, PetrichorSounds.ROOF_THICK};

    // ------------------------------------------------------------------------------------------------------------
    // Ground and surfaces
    // ------------------------------------------------------------------------------------------------------------

    /** How much a rain-hit block at this distance counts: near ground dominates, far ground still adds up. */
    private static float weight(double distance) {
        return (float) (1.0 / (1.0 + distance / 6.0));
    }

    /** How much rain on a material sounds like the open-ground bed (the rest comes from the material's own source). */
    private static float bedShare(SoundMaterial material) {
        return switch (material) {
            case SOFT -> 1.0F;
            case HARD, PUDDLE -> 0.6F;
            case WATER -> 0.5F;
            case WOOD, FABRIC -> 0.4F;
            case METAL, GLASS -> 0.3F;
            default -> 0.0F;
        };
    }

    private void scanGround(ClientLevel level, Columns columns, Puddles puddles, Vec3 eye) {
        Arrays.fill(bedWeight, 0.0F);
        Arrays.fill(bedReference, 0.0F);
        for (double[] near : bedNear) {
            near[3] = Double.MAX_VALUE;
        }
        for (double[] centroid : bedCentroid) {
            Arrays.fill(centroid, 0.0);
        }
        for (int m = 0; m < ACCENTS.length; m++) {
            Arrays.fill(accentWeight[m], 0.0F);
            for (int a = 0; a < ACCENT_SECTORS; a++) {
                accentNear[m][a][3] = Double.MAX_VALUE;
                Arrays.fill(accentCentroid[m][a], 0.0);
            }
        }
        int ex = Mth.floor(eye.x);
        int ez = Mth.floor(eye.z);
        for (int dz = -RADIUS; dz <= RADIUS; dz++) {
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                if (dx * dx + dz * dz > RADIUS * RADIUS || dx == 0 && dz == 0) {
                    continue;
                }
                double angle = Math.atan2(dz, dx) + Math.PI;
                int sector = Math.min(SECTORS - 1, (int) (angle / (Math.PI * 2.0) * SECTORS));
                int accentSector = Math.min(ACCENT_SECTORS - 1, (int) (angle / (Math.PI * 2.0) * ACCENT_SECTORS));
                double flat = Math.sqrt(dx * dx + dz * dz);
                bedReference[sector] += weight(flat);
                int x = ex + dx;
                int z = ez + dz;
                if (columns.precipitation(x, z) != Columns.RAIN) {
                    continue;
                }
                int h = columns.height(x, z);
                double dy = h - eye.y;
                if (dy > 8.0 || dy < -14.0) {
                    continue;
                }
                pos.set(x, h - 1, z);
                SoundMaterial material = SoundMaterial.of(level.getBlockState(pos));
                if (material == SoundMaterial.LEAVES || material == SoundMaterial.SILENT) {
                    continue;
                }
                double distance = Math.sqrt(flat * flat + dy * dy * 0.6);
                float w = weight(distance);
                float puddle = 0.0F;
                if ((material == SoundMaterial.SOFT || material == SoundMaterial.HARD) && puddles.coverAt(x + 0.5, h, z + 0.5) > 0.5F) {
                    puddle = 0.6F;
                }
                float share = bedShare(material) * (1.0F - puddle) + bedShare(SoundMaterial.PUDDLE) * puddle;
                bedWeight[sector] += w * share;
                collect(bedNear[sector], bedCentroid[sector], x, h, z, distance, w);
                int m = accentIndex(material);
                if (m >= 0) {
                    accentWeight[m][accentSector] += w * (1.0F - puddle);
                    collect(accentNear[m][accentSector], accentCentroid[m][accentSector], x, h, z, distance, w);
                }
                if (puddle > 0.0F) {
                    int p = ACCENTS.length - 1;
                    accentWeight[p][accentSector] += w * puddle;
                    collect(accentNear[p][accentSector], accentCentroid[p][accentSector], x, h, z, distance, w);
                }
            }
        }
        for (int s = 0; s < SECTORS; s++) {
            Voice voice = bed[s];
            float amount = bedReference[s] > 0.0F ? Math.min(1.0F, bedWeight[s] / bedReference[s] * 1.15F) : 0.0F;
            locate(level, eye, voice, bedNear[s], bedCentroid[s], amount);
        }
        for (int m = 0; m < ACCENTS.length; m++) {
            for (int a = 0; a < ACCENT_SECTORS; a++) {
                float amount = Math.min(1.0F, accentWeight[m][a] / ACCENT_FULL);
                locate(level, eye, accents[m][a], accentNear[m][a], accentCentroid[m][a], amount < 0.03F ? 0.0F : amount);
            }
        }
    }

    private static int accentIndex(SoundMaterial material) {
        for (int i = 0; i < ACCENTS.length - 1; i++) {
            if (ACCENTS[i] == material) {
                return i;
            }
        }
        return -1;
    }

    /** Keeps the nearest column ({x, y, z, distance}) and sums a centroid pulled towards the near columns. */
    private static void collect(double[] near, double[] centroid, int x, int h, int z, double distance, float w) {
        if (distance < near[3]) {
            near[0] = x + 0.5;
            near[1] = h + 0.3;
            near[2] = z + 0.5;
            near[3] = distance;
        }
        double pull = w * w * w;
        centroid[0] += (x + 0.5) * pull;
        centroid[1] += (h + 0.3) * pull;
        centroid[2] += (z + 0.5) * pull;
        centroid[3] += pull;
    }

    /**
     * Places a voice where its rain is heard from: the nearest such ground if it can be seen (the rain is heard through
     * the open door, not through the wall next to it), otherwise the middle of that ground, muffled by what is between.
     */
    private void locate(ClientLevel level, Vec3 eye, Voice voice, double[] near, double[] centroid, float amount) {
        if (amount <= 0.0F || near[3] == Double.MAX_VALUE) {
            voice.amount = 0.0F;
            return;
        }
        Occlusion nearOcclusion = occlusion(level, eye, near[0], near[1] + 0.4, near[2]);
        if (nearOcclusion == CLEAR || centroid[3] <= 0.0) {
            voice.place(near[0], near[1], near[2], amount);
            apply(voice, nearOcclusion);
            return;
        }
        double cx = centroid[0] / centroid[3];
        double cy = centroid[1] / centroid[3];
        double cz = centroid[2] / centroid[3];
        Occlusion middle = occlusion(level, eye, cx, cy + 0.4, cz);
        if (middle.volume() > nearOcclusion.volume()) {
            voice.place(cx, cy, cz, amount);
            apply(voice, middle);
        } else {
            voice.place(near[0], near[1], near[2], amount);
            apply(voice, nearOcclusion);
        }
    }

    private static void apply(Voice voice, Occlusion occlusion) {
        voice.occlusion = Muffler.enabled() ? occlusion.volume() : Math.min(1.0F, occlusion.volume() * 0.65F + (occlusion == CLEAR ? 0.35F : 0.0F));
        voice.highs = occlusion.highs();
    }

    /** What stands between the ear and a point: nothing, something thin (glass, leaves, a door), a wall or more. */
    private Occlusion occlusion(ClientLevel level, Vec3 eye, double x, double y, double z) {
        Vec3 target = new Vec3(x, y, z);
        BlockHitResult hit = clip(level, eye, target);
        if (hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(target) < 2.25) {
            return CLEAR;
        }
        BlockState state = level.getBlockState(hit.getBlockPos());
        boolean thin = thin(level, hit.getBlockPos(), state);
        // Look past the first obstacle: a second wall (or a thick one) muffles even more.
        Vec3 direction = target.subtract(eye).normalize();
        Vec3 beyond = hit.getLocation().add(direction.scale(1.05));
        if (beyond.distanceToSqr(target) < 2.25 || beyond.subtract(eye).lengthSqr() >= target.subtract(eye).lengthSqr()) {
            return thin ? THIN : SOLID;
        }
        BlockHitResult second = clip(level, beyond, target);
        if (second.getType() == HitResult.Type.MISS || second.getLocation().distanceToSqr(target) < 2.25) {
            return thin ? THIN : SOLID;
        }
        return thin && thin(level, second.getBlockPos(), level.getBlockState(second.getBlockPos())) ? SOLID : DEEP;
    }

    private static BlockHitResult clip(ClientLevel level, Vec3 from, Vec3 to) {
        return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
    }

    /** Sound passes such blocks easily. */
    private static boolean thin(ClientLevel level, BlockPos pos, BlockState state) {
        SoundMaterial material = SoundMaterial.of(state);
        return material == SoundMaterial.GLASS || material == SoundMaterial.LEAVES || material == SoundMaterial.FABRIC
                || state.is(BlockTags.DOORS) || state.is(BlockTags.TRAPDOORS) || state.is(BlockTags.FENCES)
                || state.is(BlockTags.FENCE_GATES) || !state.isCollisionShapeFullBlock(level, pos);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Crowns, roof, windows
    // ------------------------------------------------------------------------------------------------------------

    /** Up to three rain-hit tree crowns nearby, the nearest one in each third of the circle. */
    private void scanCanopy(ClientLevel level, Columns columns, Vec3 eye) {
        double[] best = new double[CANOPY_SOURCES];
        int[] count = new int[CANOPY_SOURCES];
        double[][] spot = new double[CANOPY_SOURCES][3];
        Arrays.fill(best, Double.MAX_VALUE);
        int ex = Mth.floor(eye.x);
        int ez = Mth.floor(eye.z);
        for (int oz = -CANOPY_RANGE; oz <= CANOPY_RANGE; oz += 2) {
            for (int ox = -CANOPY_RANGE; ox <= CANOPY_RANGE; ox += 2) {
                int x = ex + ox;
                int z = ez + oz;
                if (columns.precipitation(x, z) != Columns.RAIN) {
                    continue;
                }
                int h = columns.height(x, z);
                if (h < eye.y - 6.0 || h > eye.y + 24.0) {
                    continue;
                }
                pos.set(x, h - 1, z);
                if (SoundMaterial.of(level.getBlockState(pos)) != SoundMaterial.LEAVES) {
                    continue;
                }
                double angle = Math.atan2(oz, ox) + Math.PI;
                int sector = Math.min(CANOPY_SOURCES - 1, (int) (angle / (Math.PI * 2.0) * CANOPY_SOURCES));
                count[sector]++;
                double distance = ox * ox + oz * oz + (h - eye.y) * (h - eye.y) * 0.5;
                if (distance < best[sector]) {
                    best[sector] = distance;
                    spot[sector][0] = x + 0.5;
                    spot[sector][1] = h - 0.5;
                    spot[sector][2] = z + 0.5;
                }
            }
        }
        for (int c = 0; c < CANOPY_SOURCES; c++) {
            Voice voice = canopy[c];
            if (count[c] == 0) {
                voice.amount = 0.0F;
                continue;
            }
            voice.place(spot[c][0], spot[c][1], spot[c][2], Math.min(1.0F, count[c] / 10.0F));
            Occlusion occlusion = occlusion(level, eye, spot[c][0], spot[c][1], spot[c][2]);
            // Under the crown itself the leaves around the ear do not count as a wall.
            apply(voice, occlusion == THIN ? CLEAR : occlusion);
        }
    }

    /**
     * The roof right above the listener and what it is made of. Thin roofs of metal, planks, glass or wool are heard
     * as such; a roof of stone, earth or several layers is a deep, muffled rumble.
     */
    private void scanRoof(ClientLevel level, Columns columns, Vec3 eye) {
        int ex = Mth.floor(eye.x);
        int ez = Mth.floor(eye.z);
        int h = columns.height(ex, ez);
        double distance = h - eye.y;
        roof = Roof.NONE;
        overhead.amount = 0.0F;
        if (columns.precipitation(ex, ez) != Columns.RAIN || distance < 0.5 || distance > 20.0) {
            return;
        }
        pos.set(ex, h - 1, ez);
        SoundMaterial top = SoundMaterial.of(level.getBlockState(pos));
        if (top == SoundMaterial.LEAVES) {
            return;
        }
        int layers = 0;
        for (int y = Mth.floor(eye.y) + 1; y < h; y++) {
            pos.set(ex, y, ez);
            if (!level.getBlockState(pos).isAir()) {
                layers++;
            }
        }
        roof = layers > 2 ? Roof.THICK : switch (top) {
            case METAL -> Roof.METAL;
            case WOOD -> Roof.WOOD;
            case GLASS -> Roof.GLASS;
            case FABRIC -> Roof.FABRIC;
            default -> Roof.THICK;
        };
        // A wide roof drums from all over, a small awning only a little.
        int covered = 0;
        for (int oz = -2; oz <= 2; oz++) {
            for (int ox = -2; ox <= 2; ox++) {
                int ch = columns.height(ex + ox, ez + oz);
                if (columns.precipitation(ex + ox, ez + oz) == Columns.RAIN && ch > eye.y + 0.5 && ch < eye.y + 21.0) {
                    covered++;
                }
            }
        }
        float closeness = Mth.clamp(1.25F - (float) distance / 10.0F, 0.2F, 1.0F);
        // Under many layers (a cave, a deep cellar) the rain above fades away.
        float layering = layers > 1 && roof != Roof.THICK ? 0.75F : Math.clamp(1.0F - (layers - 2) / 6.0F, 0.0F, 1.0F);
        overhead.place(eye.x, h - 0.5, eye.z, closeness * layering * (0.45F + 0.55F * covered / 25.0F));
        overhead.occlusion = 1.0F;
        overhead.highs = layers > 1 && roof != Roof.THICK ? 0.5F : 1.0F;
    }

    /** Windows next to the listener that the rain beats on from outside, the wind driving it against them. */
    private void scanWindows(ClientLevel level, Columns columns, Vec3 eye) {
        int ex = Mth.floor(eye.x);
        int ey = Mth.floor(eye.y);
        int ez = Mth.floor(eye.z);
        // {x, y, z, distance, normal x, normal z} of every glass face with rain on its far side.
        List<double[]> faces = new ArrayList<>();
        BlockPos.MutableBlockPos side = new BlockPos.MutableBlockPos();
        for (int by = ey - 2; by <= ey + 3; by++) {
            for (int bz = ez - 6; bz <= ez + 6; bz++) {
                for (int bx = ex - 6; bx <= ex + 6; bx++) {
                    pos.set(bx, by, bz);
                    if (SoundMaterial.of(level.getBlockState(pos)) != SoundMaterial.GLASS) {
                        continue;
                    }
                    for (int d = 0; d < 4; d++) {
                        int nx = d == 0 ? 1 : d == 1 ? -1 : 0;
                        int nz = d == 2 ? 1 : d == 3 ? -1 : 0;
                        // The listener is on the dry side, the rain on the other.
                        if ((ex - bx) * nx + (ez - bz) * nz >= 0) {
                            continue;
                        }
                        side.set(bx + nx, by, bz + nz);
                        if (columns.precipitation(bx + nx, bz + nz) != Columns.RAIN || columns.height(bx + nx, bz + nz) > by + 1
                                || level.getBlockState(side).isCollisionShapeFullBlock(level, side)) {
                            continue;
                        }
                        faces.add(new double[]{bx, by, bz, eye.distanceToSqr(bx + 0.5, by + 0.5, bz + 0.5), nx, nz});
                    }
                }
            }
        }
        faces.sort((a, b) -> Double.compare(a[3], b[3]));
        // The nearest windows, a few blocks apart, so two sources do not sit on the same pane.
        double[][] chosen = new double[WINDOW_SOURCES][];
        int count = 0;
        for (double[] face : faces) {
            if (count == WINDOW_SOURCES) {
                break;
            }
            boolean apart = true;
            for (int i = 0; i < count; i++) {
                if (Math.abs(chosen[i][0] - face[0]) + Math.abs(chosen[i][1] - face[1]) + Math.abs(chosen[i][2] - face[2]) < 4) {
                    apart = false;
                }
            }
            if (apart) {
                chosen[count++] = face;
            }
        }
        float wx = ClientWeather.windX();
        float wz = ClientWeather.windZ();
        for (int i = 0; i < WINDOW_SOURCES; i++) {
            Voice voice = windows[i];
            double[] face = chosen[i];
            if (face == null) {
                voice.amount = 0.0F;
                continue;
            }
            double x = face[0] + 0.5 + face[4] * 0.45;
            double y = face[1] + 0.5;
            double z = face[2] + 0.5 + face[5] * 0.45;
            voice.place(x, y, z, 1.0F);
            // Rain travels with the wind and strikes the windows facing into it.
            float against = (float) -(wx * face[4] + wz * face[5]);
            windowHit[i] = 0.35F + 0.65F * Mth.clamp(against / 0.12F, 0.0F, 1.0F);
            BlockPos window = BlockPos.containing(face[0], face[1], face[2]);
            BlockHitResult hit = clip(level, eye, new Vec3(face[0] + 0.5, y, face[2] + 0.5));
            boolean seen = hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(window);
            apply(voice, seen ? CLEAR : SOLID);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Space: enclosure, far rain
    // ------------------------------------------------------------------------------------------------------------

    /** How closed in the listener is: walls around the ear and a roof above. */
    private void scanEnclosure(ClientLevel level, Columns columns, Vec3 eye) {
        int walls = 0;
        for (int d = 0; d < 8; d++) {
            float angle = d * Mth.TWO_PI / 8.0F;
            Vec3 to = eye.add(Mth.cos(angle) * 8.0, 0.0, Mth.sin(angle) * 8.0);
            if (clip(level, eye, to).getType() != HitResult.Type.MISS) {
                walls++;
            }
        }
        int ex = Mth.floor(eye.x);
        int ez = Mth.floor(eye.z);
        boolean covered = columns.height(ex, ez) > eye.y + 0.5;
        enclosure = walls / 8.0F * 0.6F + (covered ? 0.4F : 0.0F);
    }

    /** Rain far away in four directions, as far as the land is open and rained on. */
    private void scanFar(ClientLevel level, Columns columns, Vec3 eye) {
        int[] radii = {16, 22, 28, 36};
        float[] spread = {-0.35F, 0.0F, 0.35F};
        for (int f = 0; f < FAR_SOURCES; f++) {
            float angle = (f + 0.5F) * Mth.TWO_PI / FAR_SOURCES;
            int open = 0;
            for (int r : radii) {
                for (float offset : spread) {
                    int x = Mth.floor(eye.x + Mth.cos(angle + offset) * r);
                    int z = Mth.floor(eye.z + Mth.sin(angle + offset) * r);
                    if (columns.precipitation(x, z) == Columns.RAIN && columns.height(x, z) < eye.y + 12.0) {
                        open++;
                    }
                }
            }
            Voice voice = far[f];
            voice.place(eye.x + Mth.cos(angle) * 24.0, eye.y + 1.0, eye.z + Mth.sin(angle) * 24.0, open / 12.0F);
            Vec3 to = eye.add(Mth.cos(angle) * 10.0, 0.5, Mth.sin(angle) * 10.0);
            boolean blocked = clip(level, eye, to).getType() != HitResult.Type.MISS;
            voice.occlusion = blocked ? 0.55F : 1.0F;
            voice.highs = (blocked ? 0.25F : 1.0F) * (1.0F - enclosure * 0.5F);
        }
    }

    // ------------------------------------------------------------------------------------------------------------

    void stop() {
        for (Voice voice : bed) {
            voice.stop();
        }
        for (Voice[] row : accents) {
            for (Voice voice : row) {
                voice.stop();
            }
        }
        for (Voice voice : canopy) {
            voice.stop();
        }
        for (Voice voice : windows) {
            voice.stop();
        }
        for (Voice voice : far) {
            voice.stop();
        }
        overhead.stop();
        wind.stop();
    }

    String debugSummary() {
        StringBuilder out = new StringBuilder("bed ");
        for (Voice voice : bed) {
            out.append(String.format("%.1f%s ", voice.amount, voice.highs < 0.9F ? "m" : ""));
        }
        out.append("| ");
        for (int m = 0; m < ACCENTS.length; m++) {
            float sum = 0.0F;
            for (Voice voice : accents[m]) {
                sum += voice.amount;
            }
            if (sum > 0.0F) {
                out.append(ACCENTS[m].name().toLowerCase()).append(String.format(" %.1f ", sum));
            }
        }
        float leaves = 0.0F;
        for (Voice voice : canopy) {
            leaves += voice.amount;
        }
        int windowCount = 0;
        for (Voice voice : windows) {
            windowCount += voice.amount > 0.0F ? 1 : 0;
        }
        out.append(String.format("| leaves %.1f | roof %s %.1f | windows %d | enclosed %.1f", leaves,
                roof.name().toLowerCase(), overhead.amount, windowCount, enclosure));
        return out.toString();
    }
}
