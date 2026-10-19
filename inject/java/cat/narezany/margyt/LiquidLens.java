package cat.narezany.margyt;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;

/**
 * A drop of liquid glass: what is under it is bent, split a little into its
 * colours at the edge, and lit from above, as in ybouane/liquidglass (whose
 * WebGL shader this is, written again in AGSL). Needs Android 13; the caller
 * draws something flat where it is not there.
 */
final class LiquidLens {
    private static final String SRC = ""
            + "uniform shader bg;\n"
            + "uniform float2 uCenter;\n"
            + "uniform float2 uSize;\n"
            + "uniform float uRadius;\n"
            + "uniform float uZ;\n"
            + "uniform float uScale;\n"
            + "uniform float uAlpha;\n"
            + "uniform float uRefract;\n"
            + "uniform float uChroma;\n"
            + "uniform float uEdge;\n"
            + "uniform float uSpec;\n"
            + "float rr(float2 p, float2 b, float r) {\n"
            + "  float2 q = abs(p) - b + float2(r);\n"
            + "  return min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0))) - r;\n"
            + "}\n"
            + "float bh(float d, float z) {\n"
            + "  if (d <= 0.0) return 0.0;\n"
            + "  if (d >= z) return z;\n"
            + "  return sqrt(d * (2.0 * z - d));\n"
            + "}\n"
            + "half4 main(float2 frag) {\n"
            + "  float2 p = frag - uCenter;\n"
            + "  float2 h = uSize * 0.5;\n"
            + "  float r = min(uRadius, min(h.x, h.y));\n"
            + "  float sdf = rr(p, h, r);\n"
            + "  if (sdf > 0.0) {\n"
            + "    float d = max(rr(p - float2(0.0, 4.0 * uScale), h, r) - 1.0, 0.0) / uScale;\n"
            + "    float outer = exp(-d * d / 100.0) * 0.65;\n"
            + "    float contact = exp(-d * 0.2) * 0.35;\n"
            + "    float a = (outer + contact) * 0.30 * uAlpha;\n"
            + "    return half4(0.0, 0.0, 0.0, a);\n"
            + "  }\n"
            + "  float mask = 1.0 - smoothstep(-1.5, 0.5, sdf);\n"
            + "  float inside = -sdf;\n"
            + "  float edge = smoothstep(min(h.x, h.y) * 0.35, 0.0, inside);\n"
            + "  float zR = uZ;\n"
            + "  float e = 2.0 * uScale;\n"
            + "  float hC = bh(inside, zR);\n"
            + "  float hR = bh(-rr(p + float2(e, 0.0), h, r), zR);\n"
            + "  float hL = bh(-rr(p - float2(e, 0.0), h, r), zR);\n"
            + "  float hU = bh(-rr(p + float2(0.0, e), h, r), zR);\n"
            + "  float hD = bh(-rr(p - float2(0.0, e), h, r), zR);\n"
            + "  float2 g = float2(hR - hL, hU - hD) / (2.0 * e);\n"
            + "  float3 N = normalize(float3(-g, 1.0));\n"
            + "  float depth = smoothstep(0.0, zR, inside);\n"
            + "  float refrPow = 1.0 - 1.0 / 1.5;\n"
            + "  float thickNorm = (hC * 2.0) / max(zR * 2.0, 1.0);\n"
            + "  float2 refrPx = (g * refrPow * 2.0 + g * refrPow * thickNorm * 0.5) * uRefract * 30.0 * uScale;\n"
            + "  refrPx += (-p / max(h, float2(1.0))) * uRefract * 4.0 * uScale * depth;\n"
            + "  float caS = uChroma * 18.0 * (edge * 0.7 + 0.3) * 2.0 * uScale;\n"
            + "  float2 caD = N.xy * caS;\n"
            + "  float2 base = frag + refrPx;\n"
            + "  half3 col = half3(bg.eval(base + caD).r, bg.eval(base).g, bg.eval(base - caD).b);\n"
            + "  col *= 1.0 + 0.06 * depth;\n"
            + "  float fres = pow(1.0 - abs(N.z), 4.0);\n"
            + "  float3 V = float3(0.0, 0.0, 1.0);\n"
            + "  float3 H1 = normalize(normalize(float3(0.4, 0.7, 1.0)) + V);\n"
            + "  float sp1 = pow(max(dot(N, H1), 0.0), 90.0);\n"
            + "  float3 H2 = normalize(normalize(float3(-0.3, -0.5, 1.0)) + V);\n"
            + "  float sp2 = pow(max(dot(N, H2), 0.0), 50.0) * 0.3;\n"
            + "  float spB = pow(max(dot(N, normalize(float3(0.1, 0.3, 1.0))), 0.0), 6.0) * 0.1;\n"
            + "  float3 H4 = normalize(normalize(float3(0.0, 0.9, 0.4)) + V);\n"
            + "  float sp4 = pow(max(dot(N, H4), 0.0), 120.0) * 0.6;\n"
            + "  float spec = (sp1 + sp2 + spB + sp4) * uSpec;\n"
            + "  float stroke = smoothstep(-2.5 * uScale, -1.5 * uScale, sdf) * (1.0 - smoothstep(-1.0 * uScale, 0.0, sdf));\n"
            + "  float top = 0.5 + 0.5 * (-p.y / h.y);\n"
            + "  stroke *= 0.4 + 0.6 * top;\n"
            + "  float rim = edge * uEdge * 0.22;\n"
            + "  float glow = smoothstep(5.0 * uScale, 0.0, -sdf) * uEdge * 0.15;\n"
            + "  float envRefl = (N.y * 0.5 + 0.5) * fres * 0.08;\n"
            + "  half3 fin = col + half3(spec + rim + glow + stroke * uEdge * 0.55 + envRefl);\n"
            + "  fin = mix(fin, half3(1.0), fres * 0.2);\n"
            + "  float a = mask * uAlpha;\n"
            + "  return half4(fin * a, a);\n"
            + "}\n";

    private RuntimeShader shader;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix place = new Matrix();

    static boolean supported() {
        return android.os.Build.VERSION.SDK_INT >= 33;
    }

    /**
     * @param backdrop what is under the glass, whose top left is at (left, top) on the canvas
     * @param lens     where the glass is
     * @param unit     pixels to a dp
     */
    void draw(Canvas canvas, Bitmap backdrop, float left, float top, RectF lens, float unit, float alpha) {
        if (shader == null) shader = new RuntimeShader(SRC);
        BitmapShader under = new BitmapShader(backdrop, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        place.setTranslate(left, top);
        under.setLocalMatrix(place);
        shader.setInputShader("bg", under);
        float w = lens.width(), h = lens.height();
        shader.setFloatUniform("uCenter", lens.centerX(), lens.centerY());
        shader.setFloatUniform("uSize", w, h);
        shader.setFloatUniform("uRadius", h * 0.5f);
        shader.setFloatUniform("uZ", h * 0.5f * 0.26f);
        shader.setFloatUniform("uScale", unit);
        shader.setFloatUniform("uAlpha", alpha);
        shader.setFloatUniform("uRefract", 0.42f);
        shader.setFloatUniform("uChroma", 0.03f);
        shader.setFloatUniform("uEdge", 0.7f);
        shader.setFloatUniform("uSpec", 0.35f);
        paint.setShader(shader);
        float pad = 26f * unit;
        canvas.drawRect(lens.left - pad, lens.top - pad, lens.right + pad, lens.bottom + pad, paint);
    }
}
