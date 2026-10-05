#version 150

#moj_import <fog.glsl>

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

in float vertexDistance;
in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

// Distant curtains of rain: a tiling sheet of streaks, tinted like the haze it hangs in.
void main() {
    vec4 color = texture(Sampler0, fract(texCoord0)) * vertexColor * ColorModulator;
    if (color.a < 0.003) {
        discard;
    }
    // Only part of the fog is applied: far curtains stay as lighter streaks in the haze instead of vanishing in it.
    vec4 fogged = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
    fragColor = vec4(mix(fogged.rgb, color.rgb, 0.45), color.a);
}
