#version 150

// A full-screen quad given directly in clip space.
in vec3 Position;

out vec2 ndc;

void main() {
    gl_Position = vec4(Position.xy, 0.0, 1.0);
    ndc = Position.xy;
}
