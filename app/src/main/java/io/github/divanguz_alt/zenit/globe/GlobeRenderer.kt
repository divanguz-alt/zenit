package io.github.divanguz_alt.zenit.globe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import io.github.divanguz_alt.zenit.settings.GlobeStyle
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws the Earth with OpenGL ES 3.0: one screen-space quad ray-casts the sphere per fragment
 * (texture lookup, lighting, graticule, atmosphere), and the Illustrated style's land dots go out
 * as point sprites in a single draw call. Everything above the globe is drawn by the Compose overlay.
 */
class GlobeRenderer(private val context: Context, private val camera: GlobeCamera) : GLSurfaceView.Renderer {

    private var width = 1
    private var height = 1

    private var globeProgram = 0
    private var dotsProgram = 0
    private var quadVbo = 0
    private var dotsVbo = 0
    private var dotCount = 0
    private var dayTex = 0
    private var nightTex = 0
    private var dayTried = false
    private var nightTried = false

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        globeProgram = link(GLOBE_VS, GLOBE_FS)
        dotsProgram = link(DOTS_VS, DOTS_FS)

        val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        quadVbo = upload(floatBuffer(quad))

        val dots = loadDots()
        dotCount = dots.capacity() / 2
        dotsVbo = upload(dots)

        // Textures are (re)created lazily for the context that's current now.
        dayTex = 0; nightTex = 0; dayTried = false; nightTried = false
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w
        height = h
        GLES30.glViewport(0, 0, w, h)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val f = camera.frame ?: return
        val realistic = f.style == GlobeStyle.REALISTIC
        if (realistic && !dayTried) {
            dayTried = true
            dayTex = loadTexture("earth_day.jpg")
        }
        if (realistic && f.dayNight && !nightTried) {
            nightTried = true
            nightTex = loadTexture("earth_night.jpg")
        }

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        val cx = f.centerX
        val cy = height - f.centerY
        val lat0 = Math.toRadians(f.viewLatDeg).toFloat()
        val lon0 = Math.toRadians(f.viewLonDeg).toFloat()

        // --- Sphere + atmosphere ---
        GLES30.glUseProgram(globeProgram)
        GLES30.glUniform2f(uniform(globeProgram, "uViewport"), width.toFloat(), height.toFloat())
        GLES30.glUniform2f(uniform(globeProgram, "uCenter"), cx, cy)
        GLES30.glUniform1f(uniform(globeProgram, "uRadius"), f.radiusPx)
        GLES30.glUniform1f(uniform(globeProgram, "uLat0"), lat0)
        GLES30.glUniform1f(uniform(globeProgram, "uLon0"), lon0)
        GLES30.glUniform1i(uniform(globeProgram, "uStyle"), if (realistic) 1 else 0)
        GLES30.glUniform1i(uniform(globeProgram, "uHasDay"), if (dayTex != 0) 1 else 0)
        GLES30.glUniform1i(uniform(globeProgram, "uHasNight"), if (nightTex != 0) 1 else 0)
        GLES30.glUniform1i(uniform(globeProgram, "uDayNight"), if (f.dayNight && realistic) 1 else 0)
        val sun = sunViewVector(f)
        GLES30.glUniform3f(uniform(globeProgram, "uSun"), sun[0], sun[1], sun[2])
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, dayTex)
        GLES30.glUniform1i(uniform(globeProgram, "uDay"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, nightTex)
        GLES30.glUniform1i(uniform(globeProgram, "uNight"), 1)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(0)

        // --- Land dots (Illustrated) ---
        if (!realistic && dotCount > 0) {
            GLES30.glUseProgram(dotsProgram)
            GLES30.glUniform2f(uniform(dotsProgram, "uViewport"), width.toFloat(), height.toFloat())
            GLES30.glUniform2f(uniform(dotsProgram, "uCenter"), cx, cy)
            GLES30.glUniform1f(uniform(dotsProgram, "uRadius"), f.radiusPx)
            GLES30.glUniform1f(uniform(dotsProgram, "uLat0"), lat0)
            GLES30.glUniform1f(uniform(dotsProgram, "uLon0"), lon0)
            GLES30.glUniform1f(uniform(dotsProgram, "uPointSize"), 1.5f * f.density)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, dotsVbo)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
            GLES30.glDrawArrays(GLES30.GL_POINTS, 0, dotCount)
            GLES30.glDisableVertexAttribArray(0)
        }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    /** Sun direction in view space (x right, y up, z towards the viewer). */
    private fun sunViewVector(f: GlobeFrame): FloatArray {
        val p = Math.toRadians(f.subSolarLat)
        val l = Math.toRadians(f.subSolarLon - f.viewLonDeg)
        val s0 = sin(Math.toRadians(f.viewLatDeg))
        val c0 = cos(Math.toRadians(f.viewLatDeg))
        val x = cos(p) * sin(l)
        val y = c0 * sin(p) - s0 * cos(p) * cos(l)
        val z = s0 * sin(p) + c0 * cos(p) * cos(l)
        return floatArrayOf(x.toFloat(), y.toFloat(), z.toFloat())
    }

    private fun loadDots(): FloatBuffer = try {
        val bytes = context.assets.open("land_dots.bin").use { it.readBytes() }
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().let { src ->
            val out = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).asFloatBuffer()
            out.put(src)
            out.position(0)
            out
        }
    } catch (e: Exception) {
        Log.e(TAG, "land_dots.bin missing", e)
        floatBuffer(FloatArray(0))
    }

    private fun loadTexture(asset: String): Int {
        val maxSize = IntArray(1).also { GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, it, 0) }[0]
        val bitmap: Bitmap = try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.assets.open(asset).use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / sample > maxSize) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            context.assets.open(asset).use { BitmapFactory.decodeStream(it, null, opts) } ?: return 0
        } catch (e: Exception) {
            Log.e(TAG, "texture $asset failed", e)
            return 0
        }
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        bitmap.recycle()
        return ids[0]
    }

    private val uniformCache = HashMap<Long, Int>()
    private fun uniform(program: Int, name: String): Int {
        val key = (program.toLong() shl 32) or name.hashCode().toLong().and(0xffffffffL)
        return uniformCache.getOrPut(key) { GLES30.glGetUniformLocation(program, name) }
    }

    private fun upload(buf: FloatBuffer): Int {
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ids[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, buf.capacity() * 4, buf, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        return ids[0]
    }

    private fun floatBuffer(a: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(maxOf(4, a.size * 4)).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(a)
            position(0)
        }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(s)
            GLES30.glDeleteShader(s)
            throw IllegalStateException("Shader compile failed: $log")
        }
        return s
    }

    private fun link(vs: String, fs: String): Int {
        uniformCache.clear()
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, compile(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, compile(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glBindAttribLocation(p, 0, "aPos")
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw IllegalStateException("Program link failed: ${GLES30.glGetProgramInfoLog(p)}")
        return p
    }

    private companion object {
        const val TAG = "GlobeRenderer"

        /** Quad covering the sphere plus its 1.32 R atmosphere. */
        const val GLOBE_VS = """#version 300 es
layout(location = 0) in vec2 aPos;
uniform vec2 uViewport;
uniform vec2 uCenter;
uniform float uRadius;
out vec2 vLocal;
void main() {
    vLocal = aPos * 1.35;
    vec2 px = uCenter + vLocal * uRadius;
    gl_Position = vec4(px / uViewport * 2.0 - 1.0, 0.0, 1.0);
}
"""

        const val GLOBE_FS = """#version 300 es
precision highp float;
in vec2 vLocal;
out vec4 outColor;
uniform float uRadius;
uniform float uLat0;
uniform float uLon0;
uniform int uStyle;
uniform int uHasDay;
uniform int uHasNight;
uniform int uDayNight;
uniform vec3 uSun;
uniform sampler2D uDay;
uniform sampler2D uNight;

const float PI = 3.14159265358979;

float gridLine(float valueDeg, float stepDeg, float pxPerDeg) {
    float d = abs(fract(valueDeg / stepDeg + 0.5) - 0.5) * stepDeg;
    return 1.0 - smoothstep(0.35, 1.0, d * pxPerDeg);
}

void main() {
    float r2 = dot(vLocal, vLocal);
    float r = sqrt(r2);
    bool realistic = uStyle == 1;

    // Atmospheric glow: radial gradient 0.9 R -> 1.32 R.
    vec4 glow = vec4(0.0);
    if (r > 0.9 && r < 1.32) {
        float t = (r - 0.9) / 0.42;
        float a = (realistic ? 0.40 : 0.28) * (1.0 - t);
        vec3 c = realistic ? vec3(90.0, 165.0, 255.0) / 255.0 : vec3(70.0, 150.0, 255.0) / 255.0;
        glow = vec4(c * a, a);
    }
    // Illustrated outline stroke, 1 px at r = 1 (drawn on both sides of the limb).
    float stroke = realistic ? 0.0 : 0.25 * (1.0 - smoothstep(0.0, 1.0, abs(1.0 - r) * uRadius));
    vec4 strokeC = vec4(vec3(150.0, 200.0, 255.0) / 255.0 * stroke, stroke);

    if (r2 > 1.0) {
        outColor = strokeC + glow * (1.0 - strokeC.a);
        return;
    }

    float z = sqrt(1.0 - r2);
    float s0 = sin(uLat0), c0 = cos(uLat0);
    float lat = asin(clamp(vLocal.y * c0 + z * s0, -1.0, 1.0));
    float lon = uLon0 + atan(vLocal.x, z * c0 - vLocal.y * s0);
    vec3 n = vec3(vLocal, z);
    vec3 col;

    if (realistic) {
        vec2 uv = vec2(fract(lon / (2.0 * PI) + 0.5), 0.5 - lat / PI);
        // Gradients that ignore the u wrap, so mip selection doesn't break at the antimeridian.
        vec2 dx = dFdx(uv); vec2 dy = dFdy(uv);
        dx.x -= floor(dx.x + 0.5); dy.x -= floor(dy.x + 0.5);
        vec3 day = uHasDay == 1 ? textureGrad(uDay, uv, dx, dy).rgb : vec3(10.0, 38.0, 74.0) / 255.0;
        if (uDayNight == 1) {
            float d = dot(n, normalize(uSun));
            float dayF = smoothstep(-0.10, 0.10, d);
            vec3 lit = day * (0.18 + 0.95 * max(d, 0.0));
            vec3 night = (uHasNight == 1 ? textureGrad(uNight, uv, dx, dy).rgb * 1.35 : vec3(0.0)) + day * 0.05;
            col = mix(night, lit, dayF);
        } else {
            vec3 L = normalize(vec3(-0.42, 0.48, 0.77));
            float shade = 0.22 + 0.95 * max(0.0, dot(n, L));
            col = day * shade;
            col.b *= 1.05;
        }
        float t = clamp((r - 0.75) / 0.25, 0.0, 1.0);
        col = mix(col, vec3(110.0, 175.0, 255.0) / 255.0, 0.35 * t);
    } else {
        // Radial gradient #13233A -> #03060C, highlight up-left.
        float g = clamp((length(vLocal - vec2(-0.35, 0.40)) - 0.1) / 1.25, 0.0, 1.0);
        col = mix(vec3(19.0, 35.0, 58.0) / 255.0, vec3(3.0, 6.0, 12.0) / 255.0, g);
        // Graticule every 20 degrees, alpha 0.07.
        float latDeg = degrees(lat);
        float lonDeg = degrees(lon);
        float lonW = min(fwidth(lonDeg), fwidth(mod(lonDeg + 180.0, 360.0)));
        float latW = fwidth(latDeg);
        float lines = 0.0;
        if (abs(latDeg) <= 80.0) lines = max(lines, gridLine(lonDeg, 20.0, 1.0 / max(lonW, 1e-4)));
        if (abs(latDeg) <= 61.0) lines = max(lines, gridLine(latDeg, 20.0, 1.0 / max(latW, 1e-4)));
        col = mix(col, vec3(140.0, 180.0, 255.0) / 255.0, 0.07 * lines);
    }

    // Anti-aliased limb.
    float edge = clamp((1.0 - r) * uRadius * 1.5, 0.0, 1.0);
    vec4 sphere = vec4(col, 1.0) * edge;
    outColor = sphere + glow * (1.0 - sphere.a);
    outColor = strokeC + outColor * (1.0 - strokeC.a);
}
"""

        const val DOTS_VS = """#version 300 es
layout(location = 0) in vec2 aPos; // lat, lon in degrees
uniform vec2 uViewport;
uniform vec2 uCenter;
uniform float uRadius;
uniform float uLat0;
uniform float uLon0;
uniform float uPointSize;
out float vAlpha;
void main() {
    float p = radians(aPos.x);
    float l = radians(aPos.y) - uLon0;
    float sp = sin(p), cp = cos(p), cl = cos(l);
    float s0 = sin(uLat0), c0 = cos(uLat0);
    float x = cp * sin(l);
    float y = c0 * sp - s0 * cp * cl;
    float z = s0 * sp + c0 * cp * cl;
    if (z <= 0.0) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        gl_PointSize = 0.0;
        vAlpha = 0.0;
        return;
    }
    vAlpha = 0.12 + 0.78 * pow(z, 0.9);
    vec2 px = uCenter + vec2(x, y) * uRadius;
    gl_Position = vec4(px / uViewport * 2.0 - 1.0, 0.0, 1.0);
    gl_PointSize = uPointSize;
}
"""

        const val DOTS_FS = """#version 300 es
precision mediump float;
in float vAlpha;
out vec4 outColor;
void main() {
    vec3 c = vec3(214.0, 230.0, 255.0) / 255.0;
    outColor = vec4(c * vAlpha, vAlpha);
}
"""
    }
}
