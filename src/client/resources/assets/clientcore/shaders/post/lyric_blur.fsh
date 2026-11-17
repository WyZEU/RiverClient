#version 330

uniform sampler2D InSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform BlurConfig {
    vec2 BlurDir;
    float Radius;
};

in vec2 texCoord;
out vec4 fragColor;

// The radius-7 and radius-11 boxes convolved into one separable kernel.
// Adjacent taps share a linear sample: the same falloff needs two passes instead of four.
void main() {
    vec2 step = BlurDir / InSize;
    vec4 color = vec4(0.0);
    color += texture(InSampler, texCoord + step * -17.333333333) * 0.008695652;
    color += texture(InSampler, texCoord + step * -15.428571429) * 0.020289855;
    color += texture(InSampler, texCoord + step * -13.454545455) * 0.031884058;
    color += texture(InSampler, texCoord + step * -11.466666667) * 0.043478261;
    color += texture(InSampler, texCoord + step * -9.473684211) * 0.055072464;
    color += texture(InSampler, texCoord + step * -7.478260870) * 0.066666667;
    color += texture(InSampler, texCoord + step * -5.481481481) * 0.078260870;
    color += texture(InSampler, texCoord + step * -3.500000000) * 0.086956522;
    color += texture(InSampler, texCoord + step * -1.500000000) * 0.086956522;
    color += texture(InSampler, texCoord + step * 0.500000000) * 0.086956522;
    color += texture(InSampler, texCoord + step * 2.500000000) * 0.086956522;
    color += texture(InSampler, texCoord + step * 4.482758621) * 0.084057971;
    color += texture(InSampler, texCoord + step * 6.480000000) * 0.072463768;
    color += texture(InSampler, texCoord + step * 8.476190476) * 0.060869565;
    color += texture(InSampler, texCoord + step * 10.470588235) * 0.049275362;
    color += texture(InSampler, texCoord + step * 12.461538462) * 0.037681159;
    color += texture(InSampler, texCoord + step * 14.444444444) * 0.026086957;
    color += texture(InSampler, texCoord + step * 16.400000000) * 0.014492754;
    color += texture(InSampler, texCoord + step * 18.000000000) * 0.002898551;
    fragColor = color;
}
