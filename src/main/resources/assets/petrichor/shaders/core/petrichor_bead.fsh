#version 150

#moj_import <fog.glsl>

// The world drawn so far: a drop is a little lens that shows it upside down.
uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform vec2 ScreenSize;
// How far around itself a drop gathers the picture behind it, in drop radii.
uniform float LensPower;
uniform vec3 SkyColor;

in float vertexDistance;
in vec2 texCoord0;
in vec4 vertexColor;
in vec4 lightColor;

out vec4 fragColor;

// A drop of water drawn as pixel art, like a Minecraft texture: the quad is a grid of N x M pixels (vertex colour red
// and green, in sixteenths). UV -1..1 covers a drop; V 2..3 is the wet trail a running drop leaves (V 3 at the drop).
// Every pixel has one flat colour: the picture behind the drop, upside down and pixelated, a lighter top-left edge,
// a darker bottom-right edge and a white glint.
void main() {
    vec2 p = texCoord0;
    vec2 size = vec2(floor(vertexColor.r * 16.0 + 0.5), floor(vertexColor.g * 16.0 + 0.5));
    size = max(size, vec2(1.0));
    vec3 skyLight = SkyColor * (0.35 + 0.65 * lightColor.rgb);
    vec3 water = vec3(0.62, 0.76, 0.95) * skyLight;
    float alphaScale = vertexColor.a * ColorModulator.a;

    if (p.y > 1.5) {
        // The trail: a column of pixels fading in steps away from the drop.
        float row = floor(clamp(3.0 - p.y, 0.0, 0.999) * size.y);
        float fade = 1.0 - (row + 0.5) / size.y;
        float a = alphaScale * 0.4 * fade;
        if (a < 0.004) {
            discard;
        }
        fragColor = linear_fog(vec4(water * 0.55, a), vertexDistance, FogStart, FogEnd, FogColor);
        return;
    }

    vec2 q = clamp(p * 0.5 + 0.5, 0.0, 0.999);
    vec2 cell = floor(q * size);
    vec2 halfSize = size * 0.5;
    // Distance of the pixel's centre from the drop's centre, 1 at the edge of the drop.
    vec2 rel = (cell + 0.5 - halfSize) / halfSize;
    float dn = length(rel);
    float big = max(size.x, size.y);
    if (big >= 3.0 && dn > 0.9) {
        discard;
    }

    // The picture behind, sampled once per pixel at the pixel's centre and turned upside down.
    vec2 centre = (cell + 0.5) / size * 2.0 - 1.0;
    float unitsPerPixel = max(length(fwidth(p)) * 0.7071, 1.0e-4);
    float radiusPx = 1.0 / unitsPerPixel;
    vec2 at = gl_FragCoord.xy + (centre - p) * radiusPx - centre * radiusPx * LensPower;
    vec3 behind = texture(Sampler0, clamp(at / ScreenSize, vec2(0.001), vec2(0.999))).rgb;
    vec3 color = mix(behind * 1.05, water, 0.3);
    float alpha = 0.85;

    if (big < 2.0) {
        // A single pixel bead.
        color = mix(behind, water * 1.25, 0.5);
        alpha = 0.7;
    } else {
        bool rim = big >= 3.0 && dn > 0.9 - 1.3 / big;
        bool edge = big < 3.0 || rim;
        // Light from the top left, shade to the bottom right.
        float side = centre.y - centre.x;
        if (edge && side < -0.1) {
            color *= 0.62;
        } else if (edge && side > 0.1) {
            color = mix(color, water * 1.3, 0.35);
        }
        // The glint: one pixel near the top left.
        vec2 glint = big < 3.0 ? vec2(0.0, size.y - 1.0) : vec2(big >= 4.0 ? 1.0 : 0.0, size.y - 2.0);
        if (all(equal(cell, glint))) {
            color = vec3(0.92, 0.96, 1.0) * (0.45 + 0.55 * max(lightColor.r, lightColor.b));
            alpha = 0.95;
        }
    }

    float a = alpha * alphaScale;
    if (a < 0.004) {
        discard;
    }
    fragColor = linear_fog(vec4(color, a), vertexDistance, FogStart, FogEnd, FogColor);
}
