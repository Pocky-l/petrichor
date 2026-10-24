#version 150

#moj_import <petrichor:petrichor_noise.glsl>

// The air of a rainy day, drawn over the finished world from its depth buffer:
// - haze that thickens with distance and in low ground, so ridges behind ridges fade in layers instead of the whole
//   distance turning into one grey wall;
// - patches of heavier rain drifting with the wind through that haze (the showers you see crossing a valley);
// - an overcast sky: a cloud deck with dark rolls, fading into the haze at the horizon, with rain shafts hanging from
//   it, lit up by lightning;
// - low mist in front of it all: lying on the ground (in a forest up into the crowns), thinning with height, in slow
//   wisps between the trees.

uniform sampler2D Sampler0;

uniform mat4 InvViewProj;
uniform vec3 CameraPos;
uniform float PetrichorTime;
/** How far the wind has carried the air, in blocks. */
uniform vec2 Drift;
uniform float Haze;
uniform float Falloff;
uniform float BaseY;
uniform float Strength;
uniform float Overcast;
uniform float Gloom;
uniform float Shafts;
uniform float Glow;
uniform float Flash;
uniform vec3 SunDir;
/** Mist per block at its base, the base height and the mist's scale height above it. */
uniform float Mist;
uniform float MistY;
uniform float MistHeight;
uniform vec4 FogColor;

in vec2 ndc;

out vec4 fragColor;

const float PERIOD = 65536.0;
/** Height of the overcast deck above the camera, in blocks: far above the block clouds, like real rain clouds. */
const float DECK = 1200.0;

float fbm(vec2 p) {
    float sum = 0.0;
    float amp = 0.5;
    for (int i = 0; i < 5; i++) {
        sum += amp * petrichor_noise(p, PERIOD);
        p = p * 2.03 + vec2(17.13, 9.71);
        amp *= 0.5;
    }
    return sum / 0.97;
}

// Optical depth of the haze from the camera along {dir} for {dist} blocks. Density is uniform plus a part that
// thins out with height above BaseY, integrated exactly along the ray.
float opticalDepth(vec3 dir, float dist) {
    float start = 6.0;
    float d = max(dist - start, 0.0);
    float h0 = clamp(CameraPos.y + dir.y * start - BaseY, -60.0, 400.0);
    float k = Falloff * dir.y;
    float along = abs(k) < 1.0e-4 ? d : (1.0 - exp(-k * d)) / k;
    return Haze * (0.35 * d + 0.65 * exp(-Falloff * h0) * along);
}

// Heavier and lighter showers far away, drifting slowly with the wind, sampled where the ray crosses them. Only the
// distance along the land is affected: the air overhead and close by stays calm, so nothing visibly races past.
float showers(vec3 dir, float dist) {
    float weight = smoothstep(50.0, 180.0, dist) * (1.0 - smoothstep(0.06, 0.3, abs(dir.y))) * Shafts;
    if (weight <= 0.0) {
        return 1.0;
    }
    float span = min(dist, 900.0);
    float sum = 0.0;
    for (int i = 0; i < 4; i++) {
        float t = span * (0.4 + 0.2 * float(i));
        vec2 p = CameraPos.xz + dir.xz * t - Drift * 0.35;
        sum += fbm(p / 320.0);
    }
    float n = smoothstep(0.3, 0.72, sum / 4.0);
    return mix(1.0, 0.45 + 1.2 * n, weight);
}

// Integral of exp(-max(h, 0) / MistHeight) along the ray from height h0 rising by dy per block, for d blocks: the
// mist is uniform below its base and thins out exponentially above it. Exponents stay <= 0, so it never overflows.
float mistIntegral(float h0, float dy, float d) {
    float H = MistHeight;
    if (abs(dy) < 1.0e-4) {
        return d * exp(-max(h0, 0.0) / H);
    }
    float h1 = h0 + dy * d;
    if (dy > 0.0) {
        float below = h0 < 0.0 ? min(-h0 / dy, d) : 0.0;
        float a = max(h0, 0.0);
        float b = max(h1, 0.0);
        return below + H / dy * (exp(-a / H) - exp(-b / H));
    }
    float above = h0 > 0.0 ? min(h0 / -dy, d) : 0.0;
    float a = max(h0, 0.0);
    float b = max(h0 + dy * above, 0.0);
    return H / -dy * (exp(-b / H) - exp(-a / H)) + (d - above);
}

// Optical depth of the mist: it starts a step in front of the camera, and drifts in wisps sampled along the near
// part of the ray, slower than the wind.
float mistDepth(vec3 dir, float dist) {
    if (Mist <= 0.0) {
        return 0.0;
    }
    float start = 1.5;
    float d = max(min(dist, 600.0) - start, 0.0);
    float depth = Mist * mistIntegral(CameraPos.y + dir.y * start - MistY, dir.y, d);
    float span = min(dist, 48.0);
    float w = 0.0;
    for (int i = 0; i < 3; i++) {
        float t = span * (0.25 + 0.35 * float(i));
        vec3 p = CameraPos + dir * t;
        w += fbm((p.xz - Drift * 0.2) / 14.0 + vec2(p.y / 9.0, PetrichorTime * 0.004));
    }
    return depth * (0.5 + smoothstep(0.25, 0.75, w / 3.0));
}

void main() {
    vec2 uv = ndc * 0.5 + 0.5;
    float depth = texture(Sampler0, uv).r;
    vec4 farPoint = InvViewProj * vec4(ndc, 1.0, 1.0);
    vec3 dir = normalize(farPoint.xyz / farPoint.w);
    bool sky = depth >= 0.99999;
    float dist = 4000.0;
    if (!sky) {
        vec4 p = InvViewProj * vec4(ndc, depth * 2.0 - 1.0, 1.0);
        dist = length(p.xyz / p.w);
    }

    // The colour of the haze: the fog colour, a little brighter towards the hidden sun, cooler and lighter along
    // the horizon, darker overhead in a heavy storm.
    vec3 base = FogColor.rgb;
    float sun = pow(max(dot(dir, SunDir), 0.0), 6.0) * Glow;
    vec3 haze = base * (1.0 + 0.3 * sun) + vec3(0.035, 0.035, 0.025) * sun;
    float horizon = 1.0 - smoothstep(0.0, 0.35, abs(dir.y));
    haze = mix(haze, haze * vec3(0.95, 1.0, 1.06) + 0.015, horizon * 0.5);
    haze *= 1.0 - Gloom * 0.3 * smoothstep(0.0, 0.6, dir.y);
    haze += Flash * vec3(0.3, 0.32, 0.4);

    float rainDepth = opticalDepth(dir, dist) * showers(dir, dist);
    float fog = 1.0 - exp(-rainDepth);

    // The mist is lighter than the rain's haze: white-grey droplets catching the daylight.
    vec3 mistColor = mix(base, vec3(dot(base, vec3(0.3, 0.59, 0.11))) * 1.12 + 0.03, 0.5) * (1.0 + 0.25 * sun);
    mistColor += Flash * vec3(0.3, 0.32, 0.4);
    float mist = min(1.0 - exp(-mistDepth(dir, dist)), 0.97);

    vec4 behind;
    if (!sky) {
        behind = vec4(haze, min(fog, 0.985) * Strength);
    } else {
        vec3 color = haze;
        vec2 around = normalize(dir.xz + vec2(1.0e-5));
        if (dir.y > 0.0) {
            // The cloud deck, as high and as slow as real rain clouds: it is projected onto a plane far above (not the
            // block clouds' height), so overhead it barely drifts and towards the horizon it closes up.
            float t = DECK / max(dir.y, 0.03);
            vec2 p = dir.xz * t - Drift * 0.12;
            float broad = fbm(p / 2600.0);
            float fine = fbm(p / 800.0 + 3.7);
            float rolls = smoothstep(0.28, 0.78, broad * 0.75 + fine * 0.25);
            vec3 dark = base * (0.6 - 0.26 * Gloom);
            vec3 light = base * 1.05 + 0.02;
            vec3 cloud = mix(dark, light, rolls);
            cloud += Flash * vec3(0.55, 0.6, 0.75) * (0.4 + 0.6 * rolls);
            // Towards the horizon the deck sinks into the haze.
            color = mix(haze, cloud, smoothstep(0.03, 0.35, dir.y));
        }
        // Rain shafts hanging from the clouds down to the horizon: soft, wide and slow, a background, not a pattern.
        float shaft = smoothstep(0.45, 0.75, fbm(around * 2.2 + vec2(PetrichorTime * 0.0015, 7.3)));
        float band = (1.0 - smoothstep(0.0, 0.2, dir.y)) * smoothstep(-0.08, 0.0, dir.y);
        color = mix(color, base * 0.8, shaft * band * Shafts * 0.45);
        behind = vec4(color, Overcast * Strength);
    }
    // The mist is nearer than the haze: it lies over it.
    float alpha = 1.0 - (1.0 - mist) * (1.0 - behind.a);
    vec3 premultiplied = mistColor * mist + behind.rgb * behind.a * (1.0 - mist);
    fragColor = alpha > 1.0e-4 ? vec4(premultiplied / alpha, alpha) : vec4(0.0);
}
