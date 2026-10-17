package com.roychong.deskspirit.render

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 全屏 raymarching 着色器：音频驱动的球体动画。
 *
 * 逻辑很轻：一个全屏三角形面片 + 一个 fragment shader，不依赖任何 3D 引擎，
 * 所以旧手机也能跑。
 *
 * 有多种 [SphereStyle]：每个风格是一份独立的 fragment shader，切换时在 GL 线程上
 * 重新编译一棵程序（只用编译一份，比塞进一个大分支更省寄存器、也更好各自调参）。
 */
class SphereRenderer : GLSurfaceView.Renderer {

    @Volatile private var level = 0f
    @Volatile private var bass = 0f
    @Volatile private var mid = 0f
    @Volatile private var treble = 0f

    /** 目标风格（UI 线程写）；GL 线程在帧里取。 */
    @Volatile private var wantStyle = SphereStyle.AURORA.id
    private var builtStyle = -1

    private var program = 0
    private var quad = 0
    private var aPos = -1
    private var uRes = -1
    private var uTime = -1
    private var uLevel = -1
    private var uBass = -1
    private var uMid = -1
    private var uTreble = -1

    private var viewW = 1
    private var viewH = 1
    private val startNs = System.nanoTime()

    /** 由音频线程调用，写入当前帧的频段能量（0..1）。 */
    fun setFrame(level: Float, bass: Float, mid: Float, treble: Float) {
        this.level = level
        this.bass = bass
        this.mid = mid
        this.treble = treble
    }

    /** 由 UI 线程调用，切换外观。真正的编译发生在下一帧的 GL 线程上。 */
    fun setStyle(style: SphereStyle) {
        wantStyle = style.id
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val verts = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f
        )
        val buf: FloatBuffer = ByteBuffer.allocateDirect(verts.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(verts).position(0)
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        quad = ids[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quad)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verts.size * 4, buf, GLES30.GL_STATIC_DRAW)

        GLES30.glClearColor(0.02f, 0.027f, 0.05f, 1f)

        // 上下文可能被重建（息屏/切后台），强制重新编译。
        builtStyle = -1
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width.coerceAtLeast(1)
        viewH = height.coerceAtLeast(1)
        GLES30.glViewport(0, 0, viewW, viewH)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        if (wantStyle != builtStyle) buildStyle(wantStyle)
        if (program == 0) return

        val t = (System.nanoTime() - startNs) / 1_000_000_000f

        GLES30.glUseProgram(program)
        GLES30.glUniform2f(uRes, viewW.toFloat(), viewH.toFloat())
        GLES30.glUniform1f(uTime, t)
        GLES30.glUniform1f(uLevel, smooth(0, level, SMOOTH_LEVEL))
        GLES30.glUniform1f(uBass, smooth(1, bass, SMOOTH_BASS))
        GLES30.glUniform1f(uMid, smooth(2, mid, SMOOTH_MID))
        GLES30.glUniform1f(uTreble, smooth(3, treble, SMOOTH_TREBLE))

        if (aPos >= 0) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quad)
            GLES30.glEnableVertexAttribArray(aPos)
            GLES30.glVertexAttribPointer(aPos, 2, GLES30.GL_FLOAT, false, 0, 0)
        }
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        if (aPos >= 0) GLES30.glDisableVertexAttribArray(aPos)
    }

    /** 编译目标风格；成功才替换当前程序，失败保留旧的（不至于黑屏）。 */
    private fun buildStyle(id: Int) {
        val src = fragmentSrcFor(id)
        val next = buildProgram(VERTEX_SRC, src)
        if (next == 0) {
            Log.e(TAG, "style $id shader build failed, keep previous")
            builtStyle = id // 别每帧重试
            return
        }
        val old = program
        program = next
        builtStyle = id
        aPos = GLES30.glGetAttribLocation(program, "aPos")
        uRes = GLES30.glGetUniformLocation(program, "uRes")
        uTime = GLES30.glGetUniformLocation(program, "uTime")
        uLevel = GLES30.glGetUniformLocation(program, "uLevel")
        uBass = GLES30.glGetUniformLocation(program, "uBass")
        uMid = GLES30.glGetUniformLocation(program, "uMid")
        uTreble = GLES30.glGetUniformLocation(program, "uTreble")
        if (old != 0) GLES30.glDeleteProgram(old)
        Log.i(TAG, "sphere style → ${SphereStyle.of(id).name}")
    }

    private fun fragmentSrcFor(id: Int): String {
        val body = when (SphereStyle.of(id)) {
            SphereStyle.AURORA -> BODY_AURORA
            SphereStyle.ORBIT -> BODY_ORBIT
            SphereStyle.PLASMA -> BODY_PLASMA
            SphereStyle.WIREFRAME -> BODY_WIREFRAME
            SphereStyle.RIPPLE -> BODY_RIPPLE
            SphereStyle.BREATH -> BODY_BREATH
        }
        return PROLOGUE + body
    }

    // 简单的时间平滑，避免频段跳动太生硬
    private val smoothed = FloatArray(4)
    private fun smooth(index: Int, target: Float, k: Float): Float {
        smoothed[index] = smoothed[index] + (target - smoothed[index]) * k
        return smoothed[index].coerceIn(0f, 2f)
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES30.GL_VERTEX_SHADER, vs)
        val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
        if (v == 0 || f == 0) return 0
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v)
        GLES30.glAttachShader(p, f)
        GLES30.glLinkProgram(p)
        val status = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "link failed: " + GLES30.glGetProgramInfoLog(p))
            GLES30.glDeleteProgram(p)
            return 0
        }
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val status = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "compile failed: " + GLES30.glGetShaderInfoLog(s))
            GLES30.glDeleteShader(s)
            return 0
        }
        return s
    }

    companion object {
        private const val TAG = "SphereRenderer"
        private const val SMOOTH_LEVEL = 0.05f
        private const val SMOOTH_BASS = 0.12f
        private const val SMOOTH_MID = 0.18f
        private const val SMOOTH_TREBLE = 0.28f

        private val VERTEX_SRC = """
#version 300 es
in vec2 aPos;
void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
}
""".trimIndent()

        /** 六种风格共用的头部：uniform、噪声工具、坐标函数。 */
        private val PROLOGUE = """
#version 300 es
precision highp float;

out vec4 fragColor;

uniform vec2  uRes;
uniform float uTime;
uniform float uLevel;
uniform float uBass;
uniform float uMid;
uniform float uTreble;

#define PI 3.14159265

/** 球在屏幕上的基准半径（ballP() 的单位，即短边归一化）。
 *  与原版极光球一致：0.95/6.2 的角半径投影回来约 0.29。 */
#define R0 0.30

float hash31(vec3 p) {
    p = fract(p * 0.3183099 + 0.1);
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}

float vnoise(vec3 x) {
    vec3 i = floor(x);
    vec3 f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(hash31(i + vec3(0,0,0)), hash31(i + vec3(1,0,0)), f.x),
                   mix(hash31(i + vec3(0,1,0)), hash31(i + vec3(1,1,0)), f.x), f.y),
               mix(mix(hash31(i + vec3(0,0,1)), hash31(i + vec3(1,0,1)), f.x),
                   mix(hash31(i + vec3(0,1,1)), hash31(i + vec3(1,1,1)), f.x), f.y), f.z);
}

float fbm(vec3 p) {
    float s = 0.0;
    float a = 0.5;
    for (int i = 0; i < 5; i++) {
        s += a * vnoise(p);
        p = p * 2.02 + 11.7;
        a *= 0.5;
    }
    return s;
}

/** 屏幕坐标：短边归一，向上抬一点（给底部文字面板让位）。 */
vec2 ballP() {
    return (gl_FragCoord.xy - 0.5 * uRes) / uRes.y - vec2(0.0, 0.10);
}

/** 统一的收尾：暗角 + 柔和色调映射（与原版极光球一致）。 */
vec3 finish(vec3 col, vec2 P) {
    col *= 1.0 - 0.30 * pow(length(P), 1.6);
    col = col / (col + vec3(0.9));
    return pow(col, vec3(0.55));
}
""".trimIndent()

        /** 0 · 极光玻璃：玻璃球 + 体积云（初版）。 */
        private val BODY_AURORA = """

void main() {
    vec2 P = ballP();
    vec3 ro = vec3(0.0, 0.0, 6.2);
    vec3 rd = normalize(vec3(P, -1.9));

    float t = uTime;
    float R = 0.95 * (1.0 + 0.10 * uBass + 0.05 * uLevel);

    vec3 col = vec3(0.012, 0.017, 0.030);
    col += vec3(0.03, 0.10, 0.17) * exp(-length(P) * 3.0) * (0.30 + 0.70 * uLevel);

    float b = dot(ro, rd);
    float cc = dot(ro, ro) - R * R;
    float disc = b * b - cc;

    if (disc > 0.0) {
        float sq = sqrt(disc);
        float t0 = -b - sq;
        float t1 = -b + sq;
        vec3 p0 = ro + rd * t0;
        vec3 n = normalize(p0);

        float fres = pow(1.0 - abs(dot(-rd, n)), 2.5);

        vec3 acc = vec3(0.0);
        float trans = 1.0;
        float dt = (t1 - t0) / 26.0;
        for (int i = 0; i < 26; i++) {
            float ti = t0 + dt * (float(i) + 0.5);
            vec3 p = ro + rd * ti;
            vec3 q = p * 1.6;
            q.y += 1.2 * sin(t * 0.25);
            q.xz += 0.30 * vec2(sin(t * 0.40 + uBass * 3.0), cos(t * 0.30 + uMid * 2.0));
            float d = fbm(q + vec3(0.0, 1.0 * sin(t * 0.15), 0.0));
            d = smoothstep(0.46, 0.95, d);
            d *= (0.30 + 0.90 * uMid + 1.10 * uBass);
            d *= exp(-dot(p, p) * 0.7);
            d = clamp(d, 0.0, 1.0);
            if (d > 0.002) {
                vec3 cNear = vec3(0.15, 0.70, 1.00);
                vec3 cFar  = vec3(0.45, 0.20, 0.95);
                vec3 tint = mix(cNear, cFar, clamp(length(p) * 0.7, 0.0, 1.0));
                tint += vec3(0.35, 0.85, 1.0) * uTreble * 0.6;
                acc += trans * dt * d * tint;
                trans *= (1.0 - dt * d * 0.7);
            }
        }

        col = mix(vec3(0.02, 0.05, 0.10), vec3(0.05, 0.14, 0.26), fres) + acc * 0.9;
        col += vec3(0.15, 0.65, 1.00) * fres * (0.40 + 1.0 * uTreble);

        vec3 L = normalize(vec3(0.5, 0.8, 0.6));
        col += vec3(1.0) * pow(max(dot(reflect(-rd, n), L), 0.0), 64.0) * (0.25 + 0.7 * uTreble);
    }

    fragColor = vec4(finish(col, P), 1.0);
}
"""

        /** 1 · 轨道仪：球外三圈不同倾角的发光环（呼应 logo）。 */
        private val BODY_ORBIT = """

void main() {
    vec2 P = ballP();
    float t = uTime;

    vec3 col = vec3(0.010, 0.016, 0.030);
    col += vec3(0.02, 0.06, 0.13) * exp(-length(P) * 4.0) * (0.5 + 0.8 * uLevel);

    // 三圈轨道：各自不同倾角与转速，低频让它们转得更快。
    for (int i = 0; i < 3; i++) {
        float fi = float(i);
        float spin = t * (0.35 + 0.22 * fi) * (1.0 + 1.0 * uBass);
        float tilt = 0.22 + 0.30 * fi;

        vec2 q = P;
        float ca = cos(spin), sa = sin(spin);
        q = mat2(ca, -sa, sa, ca) * q;
        q.y /= cos(tilt);

        float r = R0 * (0.66 + 0.24 * fi + 0.22 * uLevel);
        float d = abs(length(q) - r);
        float w = R0 * (0.030 + 0.055 * (1.0 - fi / 3.0));
        float ring = w / (d + w);
        ring *= ring;

        vec3 c = mix(vec3(0.20, 0.85, 1.00), vec3(0.55, 0.35, 1.00), fi / 2.0);
        c += vec3(0.40, 1.00, 0.90) * uTreble * 0.5;
        col += ring * c * (0.45 + 0.95 * uLevel + 0.6 * uMid);
    }

    // 球心：亮点 + 柔光
    float r0 = length(P);
    col += vec3(0.60, 0.94, 1.00) * exp(-r0 * 13.0) * (1.30 + 2.20 * uLevel);
    col += vec3(0.22, 0.58, 1.00) * exp(-r0 * 4.0) * (0.25 + 0.65 * uMid);

    fragColor = vec4(finish(col, P), 1.0);
}
"""

        /** 2 · 等离子：内部对流 + 高频电弧。 */
        private val BODY_PLASMA = """

void main() {
    vec2 P = ballP();
    float t = uTime;
    float r = length(P);
    float R = R0 * (1.0 + 0.06 * uBass);

    vec3 col = vec3(0.010, 0.012, 0.026);
    col += vec3(0.05, 0.03, 0.14) * exp(-r * 3.6) * (0.4 + 0.8 * uLevel);

    if (r < R) {
        vec2 uv = P / R;
        float ang = atan(uv.y, uv.x);
        float rad = length(uv);

        vec3 q = vec3(ang * 1.6, rad * 2.2 - t * 0.25, t * 0.18);
        float n1 = fbm(q * 1.7);
        float n2 = fbm(q * 3.1 + n1 * 1.4 + vec3(0.0, t * 0.3, 0.0));

        float d = smoothstep(0.32, 0.95, n1 * 0.65 + n2 * 0.55);
        d *= (0.35 + 1.40 * uLevel + 0.80 * uBass);

        vec3 hot = mix(vec3(0.90, 0.35, 1.00), vec3(0.25, 0.85, 1.00), n2);
        float core = smoothstep(R, R * 0.20, r);
        col += d * hot * (0.40 + 1.00 * core);

        // 高频触发白色电弧
        col += smoothstep(0.88, 1.0, n2) * uTreble * vec3(1.0, 0.96, 1.0) * 1.50;

        // 边缘菲涅尔
        col += vec3(0.45, 0.22, 0.95) * pow(clamp(1.0 - (R - r) / (R * 0.30), 0.0, 1.0), 2.5) * 0.55;
    }

    // 外轮廓亮线
    col += vec3(0.35, 0.72, 1.00) * exp(-abs(r - R) * 26.0) * (0.35 + 0.95 * uTreble);

    fragColor = vec4(finish(col, P), 1.0);
}
"""

        /** 3 · 线框：经纬网格 + 扫描线，开销低。 */
        private val BODY_WIREFRAME = """

void main() {
    vec2 P = ballP();
    float t = uTime;
    float r = length(P);
    float R = R0 * (1.0 + 0.05 * uBass);

    vec3 col = vec3(0.010, 0.016, 0.028);
    col += vec3(0.02, 0.07, 0.12) * exp(-length(P) * 4.2) * (0.4 + 0.7 * uLevel);

    if (r < R) {
        float z = sqrt(max(R * R - r * r, 0.0));
        vec3 n = normalize(vec3(P, z));

        // 随低频轻转
        float a = t * 0.22 * (1.0 + 0.6 * uBass);
        float ca = cos(a), sa = sin(a);
        n.xz = mat2(ca, -sa, sa, ca) * n.xz;

        float lat = asin(clamp(n.y, -1.0, 1.0));
        float lon = atan(n.z, n.x);

        float dlat = abs(fract(lat / PI * 8.0 + 0.5) - 0.5);
        float dlon = abs(fract(lon / PI * 12.0 + 0.5) - 0.5);
        float w = 0.045;
        float line = clamp(smoothstep(w, 0.0, dlat) + smoothstep(w, 0.0, dlon), 0.0, 1.0);

        // 上下扫过的一条亮线
        float scanY = fract(t * 0.12) * 2.0 - 1.0;
        float scan = smoothstep(0.07, 0.0, abs(n.y - scanY));

        float shade = 0.35 + 0.65 * z / R;
        col += (line * 0.85 + scan * 1.60) * vec3(0.20, 0.80, 1.00)
             * shade * (0.60 + 1.00 * uLevel + 0.55 * uTreble);

        // 网格上的高频细点
        col += line * vec3(0.40, 1.00, 1.00) * uTreble * 0.35;
    }

    col += vec3(0.25, 0.70, 1.00) * exp(-abs(r - R) * 22.0) * (0.40 + 0.85 * uTreble);

    fragColor = vec4(finish(col, P), 1.0);
}
"""

        /** 4 · 水波：液面同心涟漪 + 低频大波。 */
        private val BODY_RIPPLE = """

void main() {
    vec2 P = ballP();
    float t = uTime;
    float r = length(P);

    vec3 col = vec3(0.008, 0.014, 0.028);
    col += vec3(0.02, 0.08, 0.14) * exp(-r * 3.4) * (0.45 + 0.8 * uLevel);

    // 三层同心涟漪：把波场压成「亮环 / 暗环」，读起来才是水波而不是漩涡。
    // r 加一点噪声扰动，免得环太规则像靶纸。
    float rw = r + (fbm(vec3(P * 8.0, t * 0.20)) - 0.5) * 0.030;
    float h = 0.0;
    for (int i = 0; i < 3; i++) {
        float fi = float(i);
        float k = 46.0 + 26.0 * fi;
        float sp = 6.0 + 3.0 * fi * (1.0 + 0.8 * uBass);
        h += sin(k * rw - t * sp) / (1.0 + 1.8 * fi);
    }
    float ripple = h * 0.55;
    float crest = pow(max(ripple, 0.0), 2.2);
    float trough = pow(max(-ripple, 0.0), 2.2);

    vec3 c = mix(vec3(0.15, 0.45, 0.90), vec3(0.55, 1.00, 0.98), crest);
    col += crest * c * (0.90 + 1.40 * uLevel + 0.90 * uBass);
    col -= trough * vec3(0.05, 0.10, 0.16) * (1.0 + uLevel);

    // 环形岸线与中心球核
    col += exp(-abs(r - R0) * 16.0) * vec3(0.30, 0.80, 1.00) * (0.35 + 0.90 * uTreble);
    col += vec3(0.45, 0.90, 1.00) * exp(-r * 11.0) * (0.60 + 1.20 * uLevel + 0.5 * uMid);

    fragColor = vec4(finish(col, P), 1.0);
}
"""

        /** 5 · 呼吸：极简单色光晕，最省电。 */
        private val BODY_BREATH = """

void main() {
    vec2 P = ballP();
    float r = length(P);

    float breath = 0.5 + 0.5 * sin(uTime * 0.9);
    float puls = 0.55 + 0.45 * uLevel;

    float R = R0 * (1.0 + 0.18 * breath + 0.50 * uLevel + 0.32 * uBass);

    vec3 col = vec3(0.006, 0.010, 0.020);
    col += vec3(0.06, 0.30, 0.45) * exp(-max(r - R, 0.0) * 7.0) * puls;
    col += vec3(0.30, 0.80, 1.00) * exp(-abs(r - R) * 22.0) * (0.60 + 0.90 * uTreble);
    col += vec3(0.05, 0.12, 0.22) * exp(-r * 3.4) * puls;
    col += vec3(0.35, 0.75, 1.00) * exp(-r * 16.0) * (0.30 + 0.9 * uMid);

    fragColor = vec4(finish(col, P), 1.0);
}
"""
    }
}
