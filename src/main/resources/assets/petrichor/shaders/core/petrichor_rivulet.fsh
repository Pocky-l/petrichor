#version 150

#moj_import <fog.glsl>
#moj_import <petrichor:petrichor_noise.glsl>

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform float PetrichorTime;
uniform vec3 SkyColor;
uniform float Flow;

in float vertexDistance;
in vec2 flowUv;
in vec4 vertexColor;
in vec4 lightColor;
in vec3 viewPos;

out vec4 fragColor;

// A thin sheet of running water. x of the flow coordinates goes across the stream (-1..1), y along it in blocks.
void main() {
    float across = abs(flowUv.x);
    float t = PetrichorTime;
    float coarse = petrichor_noise(vec2(flowUv.x * 1.5, flowUv.y * 2.0 - t * 2.4), 4096.0);
    float fine = petrichor_noise(vec2(flowUv.x * 3.5 + 5.0, flowUv.y * 5.0 - t * 3.6), 4096.0);
    float wobble = (coarse - 0.5) * 0.35;
    float edge = 1.0 - smoothstep(0.35, 1.0, across + wobble);
    float body = 0.55 + 0.45 * (coarse * 0.6 + fine * 0.4);
    float foam = smoothstep(0.72, 0.95, fine) * 0.6;

    vec3 toEye = normalize(-viewPos);
    float fresnel = 0.05 + 0.95 * pow(1.0 - abs(toEye.y), 4.0);
    vec3 light = lightColor.rgb;
    vec3 sky = mix(FogColor.rgb, SkyColor, 0.5) * max(light, vec3(0.08));
    vec3 water = vertexColor.rgb * 0.25 * light;

    vec4 color;
    color.rgb = mix(water, sky, clamp(0.35 + fresnel, 0.0, 1.0)) + foam * light * 0.5;
    color.a = edge * body * vertexColor.a * Flow * 0.85;
    color *= ColorModulator;
    if (color.a < 0.004) {
        discard;
    }
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
