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
 * 全屏 raymarching 着色器：玻璃球 + 体积云 + 音频驱动脉动。
 *
 * 逻辑很轻：一个全屏三角形面片 + 一个 fragment shader，不依赖任何 3D 引擎，
 * 所以旧手机也能跑（必要时后续可加半分辨率降采样）。
 */
class SphereRenderer : GLSurfaceView.Renderer {

    @Volatile private var level = 0f
    @Volatile private var bass = 0f
    @Volatile private var mid = 0f
    @Volatile private var treble = 0f

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

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = buildProgram(VERTEX_SRC, FRAGMENT_SRC)
        if (program == 0) {
            Log.e(TAG, "shader program build failed")
            return
        }
        aPos = GLES30.glGetAttribLocation(program, "aPos")
        uRes = GLES30.glGetUniformLocation(program, "uRes")
        uTime = GLES30.glGetUniformLocation(program, "uTime")
        uLevel = GLES30.glGetUniformLocation(program, "uLevel")
        uBass = GLES30.glGetUniformLocation(program, "uBass")
        uMid = GLES30.glGetUniformLocation(program, "uMid")
        uTreble = GLES30.glGetUniformLocation(program, "uTreble")

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
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width.coerceAtLeast(1)
        viewH = height.coerceAtLeast(1)
        GLES30.glViewport(0, 0, viewW, viewH)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
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

        private val FRAGMENT_SRC = """
#version 300 es
precision highp float;

out vec4 fragColor;

uniform vec2  uRes;
uniform float uTime;
uniform float uLevel;
uniform float uBass;
uniform float uMid;
uniform float uTreble;

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

void main() {
    vec2 P = (gl_FragCoord.xy - 0.5 * uRes) / uRes.y;
    vec3 ro = vec3(0.0, -0.32, 6.2);
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

    col *= 1.0 - 0.30 * pow(length(P), 1.6);
    col = col / (col + vec3(0.9));
    col = pow(col, vec3(0.55));
    fragColor = vec4(col, 1.0);
}
""".trimIndent()
    }
}
