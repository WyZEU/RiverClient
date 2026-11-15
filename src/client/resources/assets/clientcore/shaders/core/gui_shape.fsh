#version 330

// Cuts River's GUI quads to shape: a rounded rectangle, its outline, or a soft shadow / glow
// around it. Edges are smoothed over one screen pixel (fwidth), so corners stay crisp at any
// GUI scale instead of stepping in whole GUI pixels.

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

in vec4 vertexColor;
in vec2 local;
flat in vec2 halfSize;
flat in vec2 shape;
flat in float softness;
flat in float flow;

out vec4 fragColor;

// Signed distance to a rounded box: negative inside, positive outside, in GUI pixels.
float roundedBox(vec2 p, vec2 b, float r) {
    vec2 q = abs(p) - b + vec2(r);
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

void main() {
    float radius = shape.x;
    float border = shape.y;
    float d = roundedBox(local, halfSize, radius);
    float aa = max(fwidth(d), 0.0001);

    float coverage;
    if (softness > 0.0) {
        // Shadow or glow: fades out over [softness] beyond the edge, and stays out from under
        // the shape itself so a see-through panel is not darkened by its own shadow.
        float t = clamp(d / softness, 0.0, 1.0);
        coverage = (1.0 - t) * (1.0 - t) * clamp(d / aa + 0.5, 0.0, 1.0);
    } else if (border > 0.0) {
        coverage = clamp(0.5 - d / aa, 0.0, 1.0) * clamp(0.5 + (d + border) / aa, 0.0, 1.0);
    } else {
        coverage = clamp(0.5 - d / aa, 0.0, 1.0);
    }

    vec4 color = vertexColor;
    if (flow >= 0.0) {
        // Two soft lights travelling round the outline, opposite each other.
        float around = atan(local.y, local.x) / 6.2831853 + 0.5;
        float dist = abs(fract(around - flow + 0.5) - 0.5);
        dist = min(dist, abs(fract(around - flow) - 0.5));
        float band = smoothstep(0.16, 0.0, dist);
        color.rgb = mix(color.rgb, mix(color.rgb, vec3(1.0), 0.6), band);
        color.a = mix(color.a, 1.0, band * 0.8);
    }
    color.a *= coverage;
    if (color.a <= 0.002) {
        discard;
    }
    fragColor = color * ColorModulator;
}
