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
in vec2 sheetUv;
in vec4 vertexColor;
in vec4 lightColor;

out vec4 fragColor;

// Water spilling over the edge of a step. x runs along the edge in blocks, y down the face (0 at the lip, 1 at the foot).
void main() {
    float t = PetrichorTime;
    float lip = sheetUv.y;
    // Threads of water: thin falling streaks that come and go along the edge.
    float threads = petrichor_noise(vec2(sheetUv.x * 7.0, t * 0.35), 4096.0);
    float streaks = petrichor_noise(vec2(sheetUv.x * 22.0, lip * 3.0 - t * 4.0), 4096.0);
    float body = smoothstep(0.35, 0.8, threads) * (0.55 + 0.45 * streaks);
    float fadeTop = smoothstep(0.0, 0.08, lip);
    float fadeBottom = 1.0 - smoothstep(0.75, 1.0, lip);
    float alpha = body * fadeTop * fadeBottom * vertexColor.a * Flow;

    // Mostly a dark wet film over the face, with glints of the sky on the threads of water.
    vec3 light = lightColor.rgb;
    vec3 sky = mix(FogColor.rgb, SkyColor, 0.6) * max(light, vec3(0.06));
    float glint = smoothstep(0.65, 0.95, streaks) * smoothstep(0.5, 0.9, threads);
    vec3 film = vertexColor.rgb * light * 0.12;
    vec4 color = vec4(mix(film, sky * 1.1, glint), alpha * (0.45 + 0.4 * glint));
    color *= ColorModulator;
    if (color.a < 0.003) {
        discard;
    }
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
