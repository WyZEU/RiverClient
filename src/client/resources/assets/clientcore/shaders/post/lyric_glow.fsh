#version 330

// Last step of River's lyric glow. From the sharp words and a blurred copy of them it makes a
// thin dark outline hugging the letters (crisp at any distance, since it is measured in screen
// pixels) and a soft light around them in each word's own colour. The letters themselves are
// left out: the game draws them on top.

uniform sampler2D InSampler;    // the words, blurred
uniform sampler2D OrigSampler;  // the words, sharp

layout(std140) uniform GlowConfig {
    float Strength;
    float Outline;
};

in vec2 texCoord;

out vec4 fragColor;

const vec3 OUTLINE_COLOR = vec3(0.027, 0.039, 0.078);

vec4 over(vec4 top, vec4 under) {
    float a = top.a + under.a * (1.0 - top.a);
    if (a <= 0.0) return vec4(0.0);
    return vec4((top.rgb * top.a + under.rgb * under.a * (1.0 - top.a)) / a, a);
}

void main() {
    vec2 px = 1.0 / vec2(textureSize(OrigSampler, 0));
    float sharp = texture(OrigSampler, texCoord).a;

    // The letters grown by about a pixel and a half, for the outline.
    float grown = sharp;
    for (int i = 0; i < 12; i++) {
        float angle = float(i) * 0.5235988;
        vec2 dir = vec2(cos(angle), sin(angle)) * px;
        grown = max(grown, texture(OrigSampler, texCoord + dir * 0.9).a);
        grown = max(grown, texture(OrigSampler, texCoord + dir * 1.7).a * 0.7);
    }
    float outline = clamp(grown - sharp, 0.0, 1.0) * Outline;

    // The layer was drawn with normal blending, so its colour is premultiplied: divide it out
    // to get each word's own glow colour back.
    vec4 blurred = texture(InSampler, texCoord);
    vec3 color = blurred.a > 0.0001 ? clamp(blurred.rgb / blurred.a, 0.0, 1.0) : vec3(1.0);
    float glow = (1.0 - exp(-blurred.a * Strength)) * (1.0 - grown);

    vec4 result = over(vec4(OUTLINE_COLOR, outline), vec4(color, clamp(glow, 0.0, 1.0)));
    result.a *= 1.0 - smoothstep(0.5, 1.0, sharp);
    fragColor = result;
}
