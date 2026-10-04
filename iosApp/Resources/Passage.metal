#include <metal_stdlib>
using namespace metal;

[[ stitchable ]] half4 passageGlow(float2 position, half4 color, float2 size, float time, float active) {
    float2 uv = position / max(size, float2(1.0));
    float light = (0.5 + 0.5 * sin(time * 0.75 + uv.x * 1.8 + uv.y)) * 0.028 * active;
    return half4(min(color.rgb + half3(light * color.a), half3(color.a)), color.a);
}
