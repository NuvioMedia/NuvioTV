package com.nuvio.tv.core.player

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Draws a Dolby Vision Profile 5 stream as HDR10, with its own GL, between the decoder and the
 * player's SurfaceView.
 *
 * The decoder renders into [inputSurface] (a SurfaceTexture). Each frame is sampled as the raw
 * decoded values (EXT_YUV_target), looked up in the 3D LUT of the RPU mapping of that frame (see
 * [DolbyVisionP5Session]) and drawn into the SurfaceView, whose buffers are marked as HDR10 with
 * ANativeWindow_setBuffersDataSpace. That needs neither Media3's effect pipeline nor EGL's BT.2020
 * PQ colour space, which many TV GPUs lack.
 *
 * All GL work runs on one thread; the public methods block until it is done.
 */
@UnstableApi
internal class DolbyVisionP5SurfaceRenderer(private val session: DolbyVisionP5Session) {

    private val thread = HandlerThread("DvP5Render").apply { start() }
    private val handler = Handler(thread.looper)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var placeholderSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var windowSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var tenBitOutput = false

    private var program = 0
    private var positionLocation = -1
    private var texTransformLocation = -1
    private var texSamplerLocation = -1
    private var lutLocation = -1
    private var lutSizeLocation = -1
    private var enabledLocation = -1
    private var inputTexture = 0
    private var lutTexture = 0
    private var uploadedHash = 0L
    private var surfaceTexture: SurfaceTexture? = null
    private val texTransform = FloatArray(16)
    private val vertices: FloatBuffer = ByteBuffer.allocateDirect(QUAD.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(QUAD).position(0) }
    private val lutUpload: ShortBuffer = ByteBuffer.allocateDirect(LUT_VALUES * 2)
        .order(ByteOrder.nativeOrder()).asShortBuffer()
    private var frameErrorLogged = false

    private var holder: SurfaceHolder? = null
    private val holderCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) = setOutput(holder.surface)
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
            setOutput(holder.surface)
        // Must be done with the surface before this returns.
        override fun surfaceDestroyed(holder: SurfaceHolder) = setOutput(null)
    }

    /** The surface the decoder renders into. Valid once [start] returned true. */
    lateinit var inputSurface: Surface
        private set

    /** Tells the session which media time each frame has, by the time it is released at. */
    val frameMetadataListener = VideoFrameMetadataListener { presentationTimeUs, releaseTimeNs, _, _ ->
        session.onFrameReleased(presentationTimeUs, releaseTimeNs)
    }

    /** Sets up GL and [inputSurface]. False when this device can't run it. */
    fun start(): Boolean = runOnGlThread {
        try {
            setUp()
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "Renderer setup failed: ${e.message}")
            tearDown()
            false
        }
    } ?: false

    /** Draws into [surfaceHolder]'s surface from now on, following it as it is created and destroyed. */
    fun followOutput(surfaceHolder: SurfaceHolder) {
        holder?.removeCallback(holderCallback)
        holder = surfaceHolder
        surfaceHolder.addCallback(holderCallback)
        surfaceHolder.surface?.takeIf { it.isValid }?.let(::setOutput)
    }

    fun release() {
        holder?.removeCallback(holderCallback)
        holder = null
        runOnGlThread { tearDown(); true }
        thread.quitSafely()
    }

    private fun setOutput(surface: Surface?) {
        runOnGlThread { attachOutput(surface); true }
    }

    private fun <T> runOnGlThread(block: () -> T): T? {
        if (Thread.currentThread() === thread) return block()
        var result: T? = null
        val done = CountDownLatch(1)
        if (!handler.post {
                try {
                    result = block()
                } finally {
                    done.countDown()
                }
            }
        ) {
            return null
        }
        if (!done.await(GL_WAIT_MS, TimeUnit.MILLISECONDS)) Log.w(TAG, "GL thread didn't answer in time")
        return result
    }

    private fun setUp() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }
        val config = chooseConfig(tenBit = true)?.also { tenBitOutput = true }
            ?: chooseConfig(tenBit = false)
            ?: error("no EGL config")
        eglConfig = config
        eglContext = EGL14.eglCreateContext(
            eglDisplay, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        placeholderSurface = EGL14.eglCreatePbufferSurface(
            eglDisplay, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
        )
        check(EGL14.eglMakeCurrent(eglDisplay, placeholderSurface, placeholderSurface, eglContext)) {
            "eglMakeCurrent failed"
        }
        setUpGl()
        val texture = SurfaceTexture(inputTexture)
        texture.setOnFrameAvailableListener({ drawFrame() }, handler)
        surfaceTexture = texture
        inputSurface = Surface(texture)
        Log.i(TAG, "Renderer ready: tenBitOutput=$tenBitOutput")
    }

    private fun chooseConfig(tenBit: Boolean): EGLConfig? {
        val bits = if (tenBit) 10 else 8
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, bits,
            EGL14.EGL_GREEN_SIZE, bits,
            EGL14.EGL_BLUE_SIZE, bits,
            EGL14.EGL_ALPHA_SIZE, if (tenBit) 2 else 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(16)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attributes, 0, configs, 0, configs.size, count, 0)) return null
        val value = IntArray(1)
        // EGL also returns deeper configs: take one with exactly the asked depth.
        return (0 until count[0]).mapNotNull { configs[it] }.firstOrNull { config ->
            EGL14.eglGetConfigAttrib(eglDisplay, config, EGL14.EGL_RED_SIZE, value, 0) && value[0] == bits
        }
    }

    private fun attachOutput(surface: Surface?) {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return
        if (windowSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, placeholderSurface, placeholderSurface, eglContext)
            EGL14.eglDestroySurface(eglDisplay, windowSurface)
            windowSurface = EGL14.EGL_NO_SURFACE
        }
        if (surface == null || !surface.isValid) return
        windowSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0)
        if (windowSurface == EGL14.EGL_NO_SURFACE) {
            Log.w(TAG, "eglCreateWindowSurface failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
            return
        }
        // After EGL set the window up, so it keeps this dataspace.
        val hdr10 = DolbyVisionP5Native.setHdr10Dataspace(surface)
        session.onOutputCreated(hdr10)
        Log.i(TAG, "Output attached: hdr10=$hdr10 tenBit=$tenBitOutput")
    }

    private fun drawFrame() {
        val texture = surfaceTexture ?: return
        try {
            texture.updateTexImage()
            texture.getTransformMatrix(texTransform)
            val releaseTimeNs = texture.timestamp
            if (windowSurface == EGL14.EGL_NO_SURFACE) return
            EGL14.eglMakeCurrent(eglDisplay, windowSurface, windowSurface, eglContext)
            val size = IntArray(2)
            EGL14.eglQuerySurface(eglDisplay, windowSurface, EGL14.EGL_WIDTH, size, 0)
            EGL14.eglQuerySurface(eglDisplay, windowSurface, EGL14.EGL_HEIGHT, size, 1)
            val mapped = prepareLut(session.mediaTimeOfRelease(releaseTimeNs))

            GLES30.glViewport(0, 0, size[0], size[1])
            GLES30.glUseProgram(program)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTexture)
            GLES30.glUniform1i(texSamplerLocation, 0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)
            GLES30.glUniform1i(lutLocation, 1)
            GLES30.glUniform1f(lutSizeLocation, DolbyVisionP5Session.LUT_SIZE.toFloat())
            GLES30.glUniform1i(enabledLocation, if (mapped) 1 else 0)
            GLES30.glUniformMatrix4fv(texTransformLocation, 1, false, texTransform, 0)
            vertices.position(0)
            GLES30.glVertexAttribPointer(positionLocation, 4, GLES30.GL_FLOAT, false, 0, vertices)
            GLES30.glEnableVertexAttribArray(positionLocation)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            GLES30.glDisableVertexAttribArray(positionLocation)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)

            // Shown when the player meant it to be, not as soon as it is drawn.
            EGLExt.eglPresentationTimeANDROID(eglDisplay, windowSurface, releaseTimeNs)
            if (!EGL14.eglSwapBuffers(eglDisplay, windowSurface)) {
                logFrameErrorOnce("eglSwapBuffers failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
                return
            }
            session.onFrameDrawn(mapped)
        } catch (e: RuntimeException) {
            logFrameErrorOnce("Frame failed: ${e.message}")
        }
    }

    /** Makes sure the LUT of the frame at [mediaTimeUs] is uploaded; false: draw it unmapped. */
    private fun prepareLut(mediaTimeUs: Long): Boolean {
        val hash = session.mappingAt(mediaTimeUs)
        if (hash == 0L) return false
        if (hash != uploadedHash) {
            // Before the first mapped frame, build in place rather than show it unmapped.
            val lut = session.lutOrNull(hash) ?: if (uploadedHash == 0L) session.buildNow(hash) else null
            if (lut != null) {
                upload(lut)
                uploadedHash = hash
            }
        }
        // Ready the next scenes' LUTs in the background; until then the last one stays in use.
        session.request(listOf(hash) + session.upcomingMappings(mediaTimeUs))
        return uploadedHash != 0L
    }

    private fun upload(lut: ShortArray) {
        lutUpload.position(0)
        lutUpload.put(lut, 0, minOf(lut.size, LUT_VALUES))
        lutUpload.position(0)
        val size = DolbyVisionP5Session.LUT_SIZE
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 2)
        GLES30.glTexSubImage3D(
            GLES30.GL_TEXTURE_3D, 0, 0, 0, 0, size, size, size,
            GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, lutUpload
        )
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    private fun setUpGl() {
        val vertexShader = compile(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = compile(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val linked = GLES30.glCreateProgram()
        GLES30.glAttachShader(linked, vertexShader)
        GLES30.glAttachShader(linked, fragmentShader)
        GLES30.glLinkProgram(linked)
        val status = IntArray(1)
        GLES30.glGetProgramiv(linked, GLES30.GL_LINK_STATUS, status, 0)
        GLES30.glDeleteShader(vertexShader)
        GLES30.glDeleteShader(fragmentShader)
        if (status[0] != GLES30.GL_TRUE) {
            val log = GLES30.glGetProgramInfoLog(linked)
            GLES30.glDeleteProgram(linked)
            error("shader link failed: $log")
        }
        program = linked
        positionLocation = GLES30.glGetAttribLocation(program, "aFramePosition")
        texTransformLocation = GLES30.glGetUniformLocation(program, "uTexTransform")
        texSamplerLocation = GLES30.glGetUniformLocation(program, "uTexSampler")
        lutLocation = GLES30.glGetUniformLocation(program, "uLut")
        lutSizeLocation = GLES30.glGetUniformLocation(program, "uLutSize")
        enabledLocation = GLES30.glGetUniformLocation(program, "uEnabled")

        val textures = IntArray(2)
        GLES30.glGenTextures(2, textures, 0)
        inputTexture = textures[0]
        lutTexture = textures[1]

        // Sampled as raw YUV: EXT_YUV_target only defines nearest sampling.
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTexture)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        val size = DolbyVisionP5Session.LUT_SIZE
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGBA16F, size, size, size, 0,
            GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, null
        )
        val error = GLES30.glGetError()
        check(error == GLES30.GL_NO_ERROR) { "GL setup error 0x${Integer.toHexString(error)}" }
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES30.GL_TRUE) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            error("shader compile failed: $log")
        }
        return shader
    }

    private fun tearDown() {
        surfaceTexture?.setOnFrameAvailableListener(null)
        surfaceTexture?.release()
        surfaceTexture = null
        if (this::inputSurface.isInitialized) inputSurface.release()
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return
        if (eglContext != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglMakeCurrent(eglDisplay, placeholderSurface, placeholderSurface, eglContext)
            if (program != 0) GLES30.glDeleteProgram(program)
            val textures = intArrayOf(inputTexture, lutTexture).filter { it != 0 }.toIntArray()
            if (textures.isNotEmpty()) GLES30.glDeleteTextures(textures.size, textures, 0)
        }
        program = 0
        inputTexture = 0
        lutTexture = 0
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (windowSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, windowSurface)
        if (placeholderSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, placeholderSurface)
        if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
        EGL14.eglReleaseThread()
        // Not eglTerminate: the default display is shared with the rest of the app.
        windowSurface = EGL14.EGL_NO_SURFACE
        placeholderSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        eglDisplay = EGL14.EGL_NO_DISPLAY
    }

    private fun logFrameErrorOnce(message: String) {
        if (frameErrorLogged) return
        frameErrorLogged = true
        Log.w(TAG, message)
    }

    private companion object {
        const val TAG = "DolbyVisionP5Renderer"
        const val GL_WAIT_MS = 2_000L
        const val EGL_OPENGL_ES3_BIT = 0x40
        val LUT_VALUES = DolbyVisionP5Session.LUT_SIZE.let { it * it * it * 4 }

        /** A full-screen triangle strip, x/y/z/w per vertex. */
        val QUAD = floatArrayOf(
            -1f, -1f, 0f, 1f,
            1f, -1f, 0f, 1f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 0f, 1f,
        )

        const val VERTEX_SHADER = """#version 300 es
in vec4 aFramePosition;
uniform mat4 uTexTransform;
out vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = (uTexTransform * vec4(aFramePosition.xy * 0.5 + 0.5, 0.0, 1.0)).xy;
}
"""

        // The extensions as Media3 enables them for its own 10-bit HDR input.
        const val FRAGMENT_SHADER = """#version 300 es
#extension GL_OES_EGL_image_external : require
#extension GL_EXT_YUV_target : require
precision highp float;
precision highp sampler3D;
uniform __samplerExternal2DY2YEXT uTexSampler;
uniform sampler3D uLut;
uniform float uLutSize;
uniform int uEnabled;
in vec2 vTexSamplingCoord;
out vec4 outColor;

void main() {
  vec3 yuv = texture(uTexSampler, vTexSamplingCoord).xyz;
  if (uEnabled == 0) {
    // No mapping yet: read it as BT.2020 full-range (wrong colours for Profile 5, but a picture).
    vec3 c = yuv - vec3(0.0, 0.5, 0.5);
    outColor = vec4(clamp(vec3(c.x + 1.4746 * c.z, c.x - 0.16455 * c.y - 0.57135 * c.z, c.x + 1.8814 * c.y), 0.0, 1.0), 1.0);
    return;
  }
  vec3 lutCoord = (yuv * (uLutSize - 1.0) + 0.5) / uLutSize;
  outColor = vec4(texture(uLut, lutCoord).rgb, 1.0);
}
"""
    }
}
