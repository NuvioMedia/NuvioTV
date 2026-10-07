package com.nuvio.tv.ui.reshaped.pillnav

// The phone pill's liquid glass (NuvioMobile features/pillnav/PillGlassShader.kt) for Android 13+ TVs:
// - the whole capsule is a lens with a circular edge profile that magnifies towards the rim;
// - faint, ordered dispersion near the rim, a vibrancy boost and a light tint;
// - a thin specular line lit from the top left, a dimmer echo bottom right;
// - the selected or focused item is a second lens that magnifies what is under it and brightens with focus.
// Differences: the backdrop is not blurred, so samples are softened in the shader, and the tint is a bit denser
// for 10-foot readability.
internal const val PillGlassShader = """
uniform shader backdrop;
uniform float2 resolution;
uniform float density;
uniform float outset;
uniform half3 tint;
uniform float4 lens;
uniform float focus;

// TVs draw the pill over the sharp screen (no blur pass), so each sample is a small 4-tap soften:
// enough to keep the labels readable, far cheaper than a blur.
half4 soft(float2 p) {
    float2 a = float2(1.5, 0.75) * density;
    float2 b = float2(-0.75, 1.5) * density;
    return (backdrop.eval(p + a) + backdrop.eval(p - a) + backdrop.eval(p + b) + backdrop.eval(p - b)) * 0.25;
}

float circleMap(float x) {
    return 1.0 - sqrt(1.0 - x * x);
}

// Signed distance to a horizontal capsule and its outward normal, packed as (distance, normal.x, normal.y).
float3 capsule(float2 local, float2 halfSize) {
    float radius = halfSize.y;
    float2 q = float2(max(abs(local.x) - halfSize.x + radius, 0.0), local.y);
    float d = length(q);
    float2 n = float2(q.x * sign(local.x), q.y) / max(d, 0.001);
    return float3(d - radius, n);
}

half4 main(float2 position) {
    float2 halfSize = resolution * 0.5 - outset;
    float3 bar = capsule(position - resolution * 0.5, halfSize);
    float sd = bar.x;
    float coverage = 1.0 - smoothstep(-0.5, 0.5, sd);
    if (coverage <= 0.0) return half4(0.0);
    float2 normal = bar.yz;

    // 0 deep inside, 1 at the rim.
    float edge = clamp(1.0 + sd / (22.0 * density), 0.0, 1.0);
    float bend = circleMap(edge) * 20.0 * density;
    float2 samplePos = position - normal * bend;
    float2 spread = normal * bend * 0.07 * edge;

    float lensMix = 0.0;
    float lensRim = 0.0;
    float2 lensNormal = float2(0.0);
    if (lens.z > lens.x) {
        float2 lensCenter = (lens.xy + lens.zw) * 0.5;
        float2 lensHalf = (lens.zw - lens.xy) * 0.5;
        float3 l = capsule(position - lensCenter, lensHalf);
        lensMix = 1.0 - smoothstep(-0.5, 0.5, l.x);
        if (lensMix > 0.0) {
            float lensEdge = clamp(1.0 + l.x / lensHalf.y, 0.0, 1.0);
            float magnify = 0.9 - 0.04 * focus;
            float2 lensSamplePos = lensCenter + (samplePos - lensCenter) * magnify
                - l.yz * circleMap(lensEdge) * lensHalf.y * 0.45;
            samplePos = mix(samplePos, lensSamplePos, lensMix);
            spread += l.yz * circleMap(lensEdge) * 1.5 * density * lensMix;
            lensRim = smoothstep(1.5 * density, 0.0, -l.x) * lensMix;
            lensNormal = l.yz;
        }
    }

    half3 color = half3(
        soft(samplePos + spread).r,
        soft(samplePos).g,
        soft(samplePos - spread).b
    );
    half luminance = dot(color, half3(0.2126, 0.7152, 0.0722));
    color = clamp(mix(half3(luminance), color, 1.45), 0.0, 1.0);
    color = mix(color, mix(half3(28.0, 28.0, 30.0) / 255.0, tint, 0.08), 0.46);

    // Thickness: the glass darkens slightly towards its lower rim, and the selected lens is a touch brighter.
    color *= 1.0 - 0.14 * pow(edge, 3.0) * max(normal.y, 0.0);
    color = mix(color, half3(1.0), (0.07 + 0.05 * focus) * lensMix);
    color += tint * 0.05 * pow(edge, 3.0);

    float2 light = float2(-0.7071, -0.7071);
    float facing = dot(normal, light);
    float rimLine = smoothstep(1.6 * density, 0.0, -sd);
    float specular = rimLine * pow(abs(facing), 1.6) * (facing > 0.0 ? 0.55 : 0.2);
    specular += pow(edge, 4.0) * max(facing, 0.0) * 0.08;
    float lensFacing = dot(lensNormal, light);
    specular += lensRim * pow(abs(lensFacing), 1.4) * (lensFacing > 0.0 ? 0.42 : 0.16);
    color += half3(specular);

    return half4(clamp(color, 0.0, 1.0) * coverage, coverage);
}
"""
