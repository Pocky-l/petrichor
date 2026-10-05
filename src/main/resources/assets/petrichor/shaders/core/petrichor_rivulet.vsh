#version 150

#moj_import <fog.glsl>

in vec3 Position;
in vec2 UV0;
in vec4 Color;
in ivec2 UV2;

uniform sampler2D Sampler2;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec3 ChunkOffset;
uniform int FogShape;

out float vertexDistance;
out vec2 flowUv;
out vec4 vertexColor;
out vec4 lightColor;
out vec3 viewPos;

void main() {
    vec3 pos = Position + ChunkOffset;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    vertexDistance = fog_distance(pos, FogShape);
    flowUv = UV0;
    vertexColor = Color;
    lightColor = texelFetch(Sampler2, UV2 / 16, 0);
    viewPos = pos;
}
