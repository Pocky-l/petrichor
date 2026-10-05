#version 150

#moj_import <fog.glsl>
#moj_import <petrichor:petrichor_noise.glsl>

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform float PetrichorTime;
uniform vec3 SkyColor;
uniform float Wetness;
uniform float Coverage;
uniform float RainAmount;

in float vertexDistance;
in vec2 worldUv;
in vec4 vertexColor;
in vec4 lightColor;
in vec3 viewPos;

out vec4 fragColor;

// Rings spreading from rain drops: cells of 0.4 blocks, each with one drop per cycle at a random spot, a random part of
// the cells active depending on how hard it rains. Returns the slope of the water surface.
vec2 ripples(vec2 uv, float time, float amount) {
    vec2 p = uv * 2.5;
    vec2 cell = floor(p);
    vec2 slope = vec2(0.0);
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 c = cell + vec2(float(x), float(y));
            vec2 key = mod(c, 640.0);
            float period = 0.7 + petrichor_hash(key + 17.0) * 0.6;
            float t = time / period + petrichor_hash(key);
            float cycle = floor(t);
            float age = fract(t);
            float active = step(petrichor_hash(key + vec2(mod(cycle, 61.0) * 7.0, 3.0)), amount);
            vec2 center = c + vec2(petrichor_hash(key + vec2(cycle * 0.37, 5.0)), petrichor_hash(key + vec2(9.0, cycle * 0.53)));
            vec2 d = p - center;
            float dist = length(d);
            float radius = age * 1.3;
            float band = 1.0 - smoothstep(0.0, 0.28, abs(dist - radius));
            float fade = (1.0 - age) * (1.0 - age);
            float wave = sin((dist - radius) * 22.0) * band * fade;
            slope += active * wave * d / (dist + 0.001);
        }
    }
    return slope;
}

void main() {
    float detail = petrichor_noise(worldUv * 2.0, 512.0) * 0.6 + petrichor_noise(worldUv * 5.0, 1280.0) * 0.4;
    float field = vertexColor.a + (detail - 0.5) * 0.24;
    float threshold = 1.05 - Wetness * 0.62 * Coverage;
    float puddle = smoothstep(threshold - 0.025, threshold + 0.025, field);
    float damp = clamp(Wetness * 1.6, 0.0, 1.0) * (0.55 + 0.45 * smoothstep(threshold - 0.3, threshold, field));

    vec2 slope = puddle > 0.0 ? ripples(worldUv, PetrichorTime, RainAmount) : vec2(0.0);
    vec3 normal = normalize(vec3(-slope.x * 0.35, 1.0, -slope.y * 0.35));
    vec3 toEye = normalize(-viewPos);
    float facing = max(dot(normal, toEye), 0.0);
    float fresnel = 0.02 + 0.98 * pow(1.0 - facing, 5.0);

    // The sky seen in the water: the horizon tint near grazing angles, the sky overhead when looking down.
    vec3 reflected = reflect(-toEye, normal);
    vec3 light = lightColor.rgb;
    vec3 sky = mix(FogColor.rgb, SkyColor, smoothstep(0.05, 0.7, reflected.y)) * max(light, vec3(0.08));
    vec3 water = vertexColor.rgb * 0.22 * light;
    float mirror = clamp(fresnel * 1.3 + 0.3, 0.0, 1.0);
    vec3 puddleColor = mix(water, sky, mirror);
    float puddleAlpha = mix(0.55, 0.93, fresnel);

    // Wet ground: a dark film with a faint sheen at grazing angles.
    vec3 filmColor = sky * fresnel * 0.9;
    float filmAlpha = damp * 0.3;

    vec4 color;
    color.rgb = mix(filmColor, puddleColor, puddle);
    color.a = mix(filmAlpha, puddleAlpha, puddle);
    color *= ColorModulator;
    if (color.a < 0.004) {
        discard;
    }
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
