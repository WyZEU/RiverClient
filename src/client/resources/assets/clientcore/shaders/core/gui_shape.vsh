#version 330

// River's GUI shapes (RiverShapes): rounded rectangles, outlines and soft shadows drawn as one
// quad each and cut to shape per pixel in gui_shape.fsh. The shape travels in spare vertex
// attributes: UV0 is the position relative to the shape's centre, UV1 its half size, UV2 its
// corner radius and outline width (both in 1/16 px), Normal.x the shadow softness (/64 px),
// Normal.y and Normal.z where a travelling light on an outline is (coarse and fine; 0 = none).

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

out vec4 vertexColor;
out vec2 local;
flat out vec2 halfSize;
flat out vec2 shape;
flat out float softness;
flat out float flow;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vertexColor = Color;
    local = UV0;
    halfSize = vec2(UV1) / 16.0;
    shape = vec2(UV2) / 16.0;
    softness = Normal.x * 64.0;
    flow = Normal.y > 0.0 ? (floor(Normal.y * 127.0 + 0.5) + clamp(Normal.z, 0.0, 1.0)) / 127.0 : -1.0;
}
