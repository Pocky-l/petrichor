#version 150

#moj_import <fog.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler2;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec3 ChunkOffset;
uniform int FogShape;

out float vertexDistance;
out vec2 worldUv;
out vec4 vertexColor;
out vec4 lightColor;
out vec3 relativePos;
out vec3 viewPos;
out vec3 flow;

void main() {
    vec3 pos = Position + ChunkOffset;
    vec4 view = ModelViewMat * vec4(pos, 1.0);
    gl_Position = ProjMat * view;

    vertexDistance = fog_distance(pos, FogShape);
    worldUv = UV0;
    vertexColor = Color;
    lightColor = texelFetch(Sampler2, UV2 / 16, 0);
    relativePos = pos;
    viewPos = view.xyz;
    // The normal carries the runoff: x and z the direction water runs, y how much of it.
    flow = Normal;
}
