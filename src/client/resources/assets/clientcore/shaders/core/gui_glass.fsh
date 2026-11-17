#version 330

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};
uniform sampler2D Sampler0;

in vec4 tint;
in vec2 local;
in vec2 screenUv;
flat in vec2 halfSize;
flat in vec2 shape;
out vec4 fragColor;

void main() {
    vec2 q = abs(local) - halfSize + shape.x;
    float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - shape.x;
    float coverage = clamp(0.5 - d / max(fwidth(d), 0.0001), 0.0, 1.0);
    if (coverage <= 0.002) discard;
    vec3 color = mix(texture(Sampler0, screenUv).rgb, tint.rgb, tint.a);
    float top = local.y + halfSize.y;
    float depth = clamp(halfSize.y * 2.0 / 3.0, 2.0, 24.0);
    float sheen = shape.y * 0.078 * clamp(1.0 - top / depth, 0.0, 1.0);
    fragColor = vec4(mix(color, vec3(1.0), sheen), coverage) * ColorModulator;
}
