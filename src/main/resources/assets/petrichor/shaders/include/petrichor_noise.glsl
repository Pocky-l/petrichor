#version 150

// Hash of an integer lattice point. Callers wrap the point first when the pattern has to tile.
float petrichor_hash(vec2 p) {
    p = fract(p * vec2(0.1031, 0.1030));
    p += dot(p, p.yx + 33.33);
    return fract((p.x + p.y) * p.x);
}

// Value noise that tiles with the given period (in lattice cells).
float petrichor_noise(vec2 x, float period) {
    vec2 i = floor(x);
    vec2 f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    float a = petrichor_hash(mod(i, period));
    float b = petrichor_hash(mod(i + vec2(1.0, 0.0), period));
    float c = petrichor_hash(mod(i + vec2(0.0, 1.0), period));
    float d = petrichor_hash(mod(i + vec2(1.0, 1.0), period));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}
