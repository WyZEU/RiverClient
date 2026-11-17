#version 330

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};
layout(std140) uniform Projection {
    mat4 ProjMat;
};

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

out vec4 tint;
out vec2 local;
out vec2 screenUv;
flat out vec2 halfSize;
flat out vec2 shape;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    screenUv = gl_Position.xy / gl_Position.w * 0.5 + 0.5;
    local = UV0;
    halfSize = vec2(UV1) / 16.0;
    shape = vec2(float(UV2.x) / 16.0, float(UV2.y));
    tint = Color;
}
