#version 150

#moj_import <fog.glsl>
#moj_import <petrichor:petrichor_noise.glsl>

uniform sampler2D SceneColor;
uniform sampler2D SceneDepth;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat4 InvProjMat;
uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform float PetrichorTime;
uniform vec3 SkyColor;
uniform float Wetness;
uniform float Coverage;
uniform float RainAmount;
uniform float Flow;
uniform float SsrSteps;

in float vertexDistance;
in vec2 worldUv;
in vec4 vertexColor;
in vec4 lightColor;
in vec3 relativePos;
in vec3 viewPos;
in vec3 flow;

out vec4 fragColor;

// Slope of tiling value noise by central differences.
vec2 noiseSlope(vec2 p, float period, float e) {
    float dx = petrichor_noise(p + vec2(e, 0.0), period) - petrichor_noise(p - vec2(e, 0.0), period);
    float dy = petrichor_noise(p + vec2(0.0, e), period) - petrichor_noise(p - vec2(0.0, e), period);
    return vec2(dx, dy) / (2.0 * e);
}

// Rings spreading from rain drops on still water: one drop per cell and cycle, a share of the cells active
// depending on how hard it rains. Returns the slope of the water surface.
vec2 ripples(vec2 uv, float time, float amount) {
    vec2 p = uv * 3.0;
    vec2 cell = floor(p);
    vec2 slope = vec2(0.0);
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 c = cell + vec2(float(x), float(y));
            vec2 key = mod(c, 768.0);
            float period = 0.55 + petrichor_hash(key + 17.0) * 0.5;
            float t = time / period + petrichor_hash(key);
            float cycle = floor(t);
            float age = fract(t);
            float active = step(petrichor_hash(key + vec2(mod(cycle, 61.0) * 7.0, 3.0)), amount);
            vec2 center = c + vec2(petrichor_hash(key + vec2(mod(cycle, 53.0) * 0.37, 5.0)),
                    petrichor_hash(key + vec2(9.0, mod(cycle, 47.0) * 0.53)));
            vec2 d = p - center;
            float dist = length(d);
            float radius = age * 1.1;
            float x0 = (dist - radius) * 26.0;
            // A couple of crests behind the front, fading as the ring grows.
            float wave = sin(x0) * exp(-x0 * x0 * 0.08) * step(x0, 6.0);
            float fade = (1.0 - age) * (1.0 - age);
            slope += active * wave * fade * d / (dist + 0.001);
        }
    }
    return slope;
}

vec3 viewFromDepth(vec2 uv, float depth) {
    vec4 ndc = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 view = InvProjMat * ndc;
    return view.xyz / view.w;
}

// Screen-space reflection: marches the reflected ray through the depth of the scene drawn so far.
// Returns the reflected colour and the confidence of the hit (0 when the ray left the screen or hit nothing).
vec4 traceReflection(vec3 origin, vec3 dir) {
    if (SsrSteps < 1.0) {
        return vec4(0.0);
    }
    float stepLength = 0.12 + length(origin) * 0.012;
    vec3 point = origin + dir * stepLength;
    vec3 previous = origin;
    for (int i = 0; i < 64; i++) {
        if (float(i) >= SsrSteps) {
            break;
        }
        vec4 clip = ProjMat * vec4(point, 1.0);
        if (clip.w <= 0.0) {
            break;
        }
        vec2 uv = clip.xy / clip.w * 0.5 + 0.5;
        if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
            break;
        }
        float depth = texture(SceneDepth, uv).r;
        if (depth < 1.0) {
            vec3 scene = viewFromDepth(uv, depth);
            float behind = scene.z - point.z;
            if (behind > 0.0 && behind < stepLength * 2.0 + 0.4) {
                // Refine between the last point in front of the scene and the first one behind it.
                vec3 a = previous;
                vec3 b = point;
                for (int r = 0; r < 5; r++) {
                    vec3 m = (a + b) * 0.5;
                    vec4 mc = ProjMat * vec4(m, 1.0);
                    vec2 muv = mc.xy / mc.w * 0.5 + 0.5;
                    vec3 ms = viewFromDepth(muv, texture(SceneDepth, muv).r);
                    if (ms.z - m.z > 0.0) {
                        b = m;
                    } else {
                        a = m;
                    }
                }
                vec4 hc = ProjMat * vec4(b, 1.0);
                vec2 huv = hc.xy / hc.w * 0.5 + 0.5;
                vec2 edge = smoothstep(0.0, 0.08, huv) * smoothstep(1.0, 0.92, huv);
                float confidence = edge.x * edge.y * (1.0 - float(i) / max(SsrSteps, 1.0) * 0.6);
                return vec4(texture(SceneColor, huv).rgb, confidence);
            }
        }
        previous = point;
        stepLength *= 1.18;
        point += dir * stepLength;
    }
    return vec4(0.0);
}

void main() {
    // Puddle outline: the block-scale field from the terrain, broken up by warped noise into organic shapes.
    vec2 warp = vec2(petrichor_noise(worldUv * 0.5, 128.0), petrichor_noise(worldUv * 0.5 + 37.0, 128.0)) - 0.5;
    vec2 wp = worldUv + warp * 1.6;
    float detail = petrichor_noise(wp, 256.0) * 0.55 + petrichor_noise(wp * 3.0, 768.0) * 0.3
            + petrichor_noise(wp * 9.0, 2304.0) * 0.15;
    float field = vertexColor.a + (detail - 0.5) * 0.34;
    float threshold = 1.05 - Wetness * 0.62 * Coverage;
    float aa = max(fwidth(field) * 1.2, 0.004);
    float puddle = smoothstep(threshold - aa, threshold + aa, field);
    float margin = smoothstep(threshold - 0.16, threshold, field) * (1.0 - puddle);
    float wet = clamp(Wetness * 1.5, 0.0, 1.0);

    // Surface normal: rain rings on puddles, running water on slopes, fine grain on wet ground.
    vec2 slope = vec2(0.0);
    if (puddle > 0.0) {
        slope += ripples(worldUv, PetrichorTime, RainAmount) * 0.35 * puddle;
        slope += noiseSlope(worldUv * 2.0 + vec2(PetrichorTime * 0.15, 0.0), 512.0, 0.05) * 0.015 * puddle;
    }
    float flowAmount = clamp(flow.y, 0.0, 1.0) * Flow * (1.0 - puddle);
    if (flowAmount > 0.01 && length(flow.xz) > 0.01) {
        vec2 along = normalize(flow.xz);
        vec2 across = vec2(-along.y, along.x);
        vec2 fp = vec2(dot(worldUv, across) * 3.0, dot(worldUv, along) * 1.2 - PetrichorTime * 1.6);
        vec2 s = noiseSlope(fp, 4096.0, 0.06);
        slope += (across * s.x + along * s.y) * 0.08 * flowAmount;
    }
    slope += noiseSlope(worldUv * 9.0, 2304.0, 0.08) * 0.02 * (1.0 - puddle);
    vec3 normal = normalize(vec3(-slope.x, 1.0, -slope.y));

    vec3 toEye = normalize(-relativePos);
    float facing = max(dot(normal, toEye), 0.0);
    float fresnel = 0.12 + 0.88 * pow(1.0 - facing, 4.0);

    // What the surface mirrors: the scene where the reflected ray finds it, the sky elsewhere.
    vec3 reflectedWorld = reflect(-toEye, normal);
    float skyLight = clamp(lightColor.b * 1.1, 0.05, 1.0);
    vec3 sky = mix(FogColor.rgb, SkyColor, smoothstep(0.0, 0.6, reflectedWorld.y)) * skyLight;
    vec3 viewNormal = normalize(mat3(ModelViewMat) * normal);
    vec3 viewDir = normalize(viewPos);
    vec4 traced = traceReflection(viewPos + viewNormal * 0.02, reflect(viewDir, viewNormal));
    vec3 mirror = mix(sky, traced.rgb, traced.a);

    // Puddles: shallow water over the ground. The ground shows through, darkened and tinted by the water, and the
    // reflection lies on top, strongest at grazing angles.
    float transmit = (1.0 - fresnel) * 0.5;
    vec3 tint = vertexColor.rgb * lightColor.rgb * 0.12;
    float puddleAlpha = 1.0 - transmit;
    vec3 puddleColor = (mirror * fresnel + tint * (1.0 - fresnel) * 0.5) / max(puddleAlpha, 0.001);

    // Wet ground: darker, with a soft sheen of the same reflection at grazing angles.
    float darken = wet * (0.26 + 0.14 * margin) + flowAmount * 0.1;
    float gloss = fresnel * wet * (0.35 + 0.4 * margin + 0.4 * flowAmount);
    float wetAlpha = 1.0 - (1.0 - darken) * (1.0 - gloss);
    vec3 wetColor = mirror * gloss / max(wetAlpha, 0.001);

    vec4 color;
    color.a = mix(wetAlpha, puddleAlpha, puddle);
    color.rgb = mix(wetColor, puddleColor, puddle);
    color *= ColorModulator;
    if (color.a < 0.003) {
        discard;
    }
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
