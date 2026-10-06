#version 150

#moj_import <fog.glsl>

// The world drawn so far: a drop is a little lens that shows it upside down.
uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform vec2 ScreenSize;
// 1 when Sampler0 holds a copy of the scene, 0 when drops must do without it.
uniform float Refraction;
// How far around itself a drop gathers the picture behind it, in drop radii.
uniform float LensPower;
uniform vec3 SkyColor;

in float vertexDistance;
in vec2 texCoord0;
in vec4 vertexColor;
in vec4 lightColor;

out vec4 fragColor;

// One drop of water lying on a surface, or (V above 1.5) the wet streak a running drop leaves behind it.
void main() {
    vec2 p = texCoord0;
    vec3 skyLight = SkyColor * (0.35 + 0.65 * lightColor.rgb);
    if (p.y > 1.5) {
        // The streak: a thin film, darkest along its middle, fading away from the drop (V 3 at the drop, 2 at the end).
        float across = abs(p.x);
        float along = clamp(3.0 - p.y, 0.0, 1.0);
        float film = (1.0 - smoothstep(0.3, 1.0, across)) * (1.0 - along) * (1.0 - along * 0.3);
        float a = film * vertexColor.a * ColorModulator.a * 0.55;
        if (a < 0.004) {
            discard;
        }
        // Wet surfaces are darker, with a faint sheen of the sky along the film.
        vec3 c = skyLight * 0.22 * (1.0 - across);
        fragColor = linear_fog(vec4(c, a), vertexDistance, FogStart, FogEnd, FogColor);
        return;
    }
    float r2 = dot(p, p);
    if (r2 >= 1.0) {
        discard;
    }
    float r = sqrt(r2);
    // Size of the drop on screen: pixels per unit of p (the drop's radius is one unit).
    float unitsPerPixel = max(length(fwidth(p)) * 0.7071, 1.0e-4);
    float radiusPx = 1.0 / unitsPerPixel;

    // The drop is a dome: steep at the rim, flat on top.
    float h = sqrt(1.0 - r2);
    vec3 n = normalize(vec3(p, h * 1.2 + 0.05));
    float rim = smoothstep(0.55, 1.0, r);

    vec3 behind;
    float alpha;
    if (Refraction > 0.5) {
        // Upside down and squeezed in: the picture comes from a wider circle around the drop.
        vec2 at = gl_FragCoord.xy - p * radiusPx * LensPower;
        behind = texture(Sampler0, clamp(at / ScreenSize, vec2(0.001), vec2(0.999))).rgb;
        // A little brighter than the surroundings: the lens gathers the light.
        behind *= 1.06;
        alpha = 1.0;
    } else {
        behind = skyLight * 0.55;
        alpha = 0.15 + rim * 0.6;
    }
    vec3 color = behind;
    // Light bending at the steep rim escapes sideways: the rim is dark.
    color *= 1.0 - rim * 0.72;
    // The sky shining through the drop pools at its bottom.
    color += skyLight * 0.22 * smoothstep(0.15, 0.95, -p.y) * (1.0 - rim);
    // A sharp glint of the sky up and to the left, and a soft reflection on the upper part.
    vec2 g = p - vec2(-0.3, 0.4);
    float glint = exp(-dot(g, g) * 45.0);
    color += vec3(glint) * (0.35 + 0.65 * max(lightColor.r, lightColor.b));
    color += skyLight * 0.12 * max(n.y, 0.0) * rim;
    alpha = max(alpha, glint);

    // Soft edge, a pixel wide whatever the size of the drop.
    float edge = clamp((1.0 - r) * radiusPx, 0.0, 1.0);
    float a = edge * alpha * vertexColor.a * ColorModulator.a;
    if (a < 0.004) {
        discard;
    }
    fragColor = linear_fog(vec4(color, a), vertexDistance, FogStart, FogEnd, FogColor);
}
