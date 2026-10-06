#version 150

#moj_import <petrichor:petrichor_noise.glsl>

// The air of a rainy day, drawn over the finished world from its depth buffer:
// - haze that thickens with distance and in low ground, so ridges behind ridges fade in layers instead of the whole
//   distance turning into one grey wall;
// - patches of heavier rain drifting with the wind through that haze (the showers you see crossing a valley);
// - an overcast sky: a cloud deck with dark rolls, fading into the haze at the horizon, with rain shafts hanging from
//   it, lit up by lightning.

uniform sampler2D Sampler0;

uniform mat4 InvViewProj;
uniform vec3 CameraPos;
uniform float PetrichorTime;
uniform vec2 Wind;
uniform float Haze;
uniform float Falloff;
uniform float BaseY;
uniform float Strength;
uniform float Overcast;
uniform float Gloom;
uniform float Shafts;
uniform float Glow;
uniform float Flash;
uniform float CloudY;
uniform vec3 SunDir;
uniform vec4 FogColor;

in vec2 ndc;

out vec4 fragColor;

const float PERIOD = 65536.0;

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

// Heavier and lighter rain drifting with the wind, sampled where the ray crosses it.
float showers(vec3 dir, float dist) {
    float span = min(max(dist - 6.0, 0.0), 900.0);
    float sum = 0.0;
    for (int i = 0; i < 4; i++) {
        float t = 6.0 + span * (0.2 + 0.2 * float(i));
        vec2 p = CameraPos.xz + dir.xz * t - Wind * PetrichorTime;
        sum += fbm(p / 260.0);
    }
    float n = smoothstep(0.3, 0.72, sum / 4.0);
    return mix(1.0, 0.35 + 1.4 * n, Shafts);
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

    if (!sky) {
        fragColor = vec4(haze, min(fog, 0.985) * Strength);
        return;
    }

    vec3 color = haze;
    vec2 around = normalize(dir.xz + vec2(1.0e-5));
    if (dir.y > 0.0) {
        // The cloud deck: broad dark rolls and finer texture, drifting with the wind.
        float t = (CloudY - CameraPos.y) / max(dir.y, 0.015);
        vec2 p = CameraPos.xz + dir.xz * t - Wind * PetrichorTime * 1.6;
        float broad = fbm(p / 900.0);
        float fine = fbm(p / 210.0 + 3.7);
        float rolls = smoothstep(0.28, 0.78, broad * 0.7 + fine * 0.3);
        vec3 dark = base * (0.58 - 0.28 * Gloom);
        vec3 light = base * 1.06 + 0.025;
        vec3 cloud = mix(dark, light, rolls);
        cloud += Flash * vec3(0.55, 0.6, 0.75) * (0.4 + 0.6 * rolls);
        // Far parts of the deck sink into the haze.
        float visible = exp(-t * Haze * 0.35) * smoothstep(0.0, 0.1, dir.y);
        color = mix(haze, cloud, clamp(visible, 0.0, 1.0));
    }
    // Rain shafts hanging from the clouds down to the horizon, streaked by the falling rain.
    float shaft = smoothstep(0.42, 0.72, fbm(around * 3.5 + vec2(PetrichorTime * 0.004, 0.0) + Wind * PetrichorTime * 0.0005));
    float streak = 0.82 + 0.18 * petrichor_noise(around * 240.0 + vec2(0.0, dir.y * 30.0 - PetrichorTime * 0.6), PERIOD);
    float band = 1.0 - smoothstep(-0.02, 0.24, dir.y);
    color = mix(color, base * 0.74 * streak, shaft * band * Shafts * 0.65);
    fragColor = vec4(color, Overcast * Strength);
}
