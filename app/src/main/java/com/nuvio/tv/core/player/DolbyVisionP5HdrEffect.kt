package com.nuvio.tv.core.player

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * Plays Dolby Vision Profile 5 as HDR10: one 3D LUT lookup per pixel, with the LUT of the RPU
 * mapping of the frame being drawn (see [DolbyVisionP5Session]).
 *
 * Media3 hands HDR frames to effects as linear BT.2020 light (1.0 = 1000 nits), computed from the
 * decoded values with its BT.2020 full-range matrix. The shader PQ-encodes that back, which is
 * what the LUT is indexed by, and decodes the LUT's HDR10 PQ output to linear light again.
 * Frames without a mapping (no RPU yet) are passed through unchanged.
 */
@UnstableApi
internal class DolbyVisionP5HdrEffect(private val session: DolbyVisionP5Session) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        DolbyVisionP5ShaderProgram(session, useHdr)
}

@UnstableApi
private class DolbyVisionP5ShaderProgram(
    private val session: DolbyVisionP5Session,
    private val useHdr: Boolean,
) : BaseGlShaderProgram(/* useHighPrecisionColorComponents= */ useHdr, /* texturePoolCapacity= */ 1) {

    private var program = 0
    private var positionLocation = -1
    private var texSamplerLocation = -1
    private var lutLocation = -1
    private var lutSizeLocation = -1
    private var enabledLocation = -1
    private var lutTexture = 0
    private var uploadedHash = 0L
    private val lutUpload: ShortBuffer = ByteBuffer
        .allocateDirect(LUT_VALUES * 2)
        .order(ByteOrder.nativeOrder())
        .asShortBuffer()
    private val vertices: FloatBuffer = GlUtil.createBuffer(GlUtil.getNormalizedCoordinateBounds())

    init {
        session.onEffectCreated(useHdr)
        if (!useHdr) Log.w(TAG, "Effect pipeline is not HDR: frames pass through unchanged")
    }

    @Throws(VideoFrameProcessingException::class)
    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        if (program == 0) {
            try {
                setUpGl()
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            } catch (e: IllegalStateException) {
                throw VideoFrameProcessingException(e)
            }
        }
        return Size(inputWidth, inputHeight)
    }

    @Throws(VideoFrameProcessingException::class)
    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            val enabled = useHdr && prepareLut(presentationTimeUs)
            session.onFrameDrawn(enabled)
            GLES30.glUseProgram(program)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inputTexId)
            GLES30.glUniform1i(texSamplerLocation, 0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)
            GLES30.glUniform1i(lutLocation, 1)
            GLES30.glUniform1f(lutSizeLocation, DolbyVisionP5Session.LUT_SIZE.toFloat())
            GLES30.glUniform1i(enabledLocation, if (enabled) 1 else 0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
            vertices.position(0)
            GLES30.glVertexAttribPointer(
                positionLocation, GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE, GLES30.GL_FLOAT,
                false, 0, vertices
            )
            GLES30.glEnableVertexAttribArray(positionLocation)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            GLES30.glDisableVertexAttribArray(positionLocation)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    @Throws(VideoFrameProcessingException::class)
    override fun release() {
        super.release()
        try {
            if (program != 0) GLES30.glDeleteProgram(program)
            if (lutTexture != 0) GlUtil.deleteTexture(lutTexture)
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        } finally {
            program = 0
            lutTexture = 0
        }
    }

    /** Makes sure the LUT of the frame at [presentationTimeUs] is uploaded; false: pass it through. */
    private fun prepareLut(presentationTimeUs: Long): Boolean {
        val mediaTimeUs = session.frameTimeMapper.toMediaTimeUs(presentationTimeUs)
            .takeIf { it != C.TIME_UNSET } ?: presentationTimeUs
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
        // Ready the next scenes' LUTs in the background. Until one is ready, the previous
        // scene's LUT stays in use: close, and far better than the unmapped colours.
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

    @Throws(GlUtil.GlException::class)
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
            throw IllegalStateException("DV5 shader link failed: $log")
        }
        program = linked
        positionLocation = GLES30.glGetAttribLocation(program, "aFramePosition")
        texSamplerLocation = GLES30.glGetUniformLocation(program, "uTexSampler")
        lutLocation = GLES30.glGetUniformLocation(program, "uLut")
        lutSizeLocation = GLES30.glGetUniformLocation(program, "uLutSize")
        enabledLocation = GLES30.glGetUniformLocation(program, "uEnabled")

        val size = DolbyVisionP5Session.LUT_SIZE
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        lutTexture = textures[0]
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
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
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GlUtil.checkGlError()
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES30.GL_TRUE) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw IllegalStateException("DV5 shader compile failed: $log")
        }
        return shader
    }

    private companion object {
        const val TAG = "DolbyVisionP5Effect"
        val LUT_VALUES = DolbyVisionP5Session.LUT_SIZE.let { it * it * it * 4 }

        const val VERTEX_SHADER = """#version 300 es
in vec4 aFramePosition;
out vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
}
"""

        // PQ (SMPTE ST 2084) as in Media3's own HDR shaders.
        const val FRAGMENT_SHADER = """#version 300 es
precision highp float;
precision highp sampler3D;
uniform sampler2D uTexSampler;
uniform sampler3D uLut;
uniform float uLutSize;
uniform int uEnabled;
in vec2 vTexSamplingCoord;
out vec4 outColor;

const float M1 = 2610.0 / 16384.0;
const float M2 = (2523.0 / 4096.0) * 128.0;
const float C1 = 3424.0 / 4096.0;
const float C2 = (2413.0 / 4096.0) * 32.0;
const float C3 = (2392.0 / 4096.0) * 32.0;
// Media3's intermediate HDR light: 1.0 is 1000 nits, PQ's 1.0 is 10000.
const float PQ_TO_INTERMEDIATE = 10.0;

vec3 pqOetf(vec3 linearColor) {
  vec3 p = pow(clamp(linearColor, 0.0, 1.0), vec3(M1));
  return pow((C1 + C2 * p) / (1.0 + C3 * p), vec3(M2));
}

vec3 pqEotf(vec3 pqColor) {
  vec3 p = pow(clamp(pqColor, 0.0, 1.0), vec3(1.0 / M2));
  return pow(max(p - C1, 0.0) / (C2 - C3 * p), vec3(1.0 / M1));
}

void main() {
  vec4 color = texture(uTexSampler, vTexSamplingCoord);
  if (uEnabled == 0) {
    outColor = color;
    return;
  }
  vec3 pq = pqOetf(color.rgb / PQ_TO_INTERMEDIATE);
  vec3 lutCoord = (pq * (uLutSize - 1.0) + 0.5) / uLutSize;
  vec3 mapped = texture(uLut, lutCoord).rgb;
  outColor = vec4(pqEotf(mapped) * PQ_TO_INTERMEDIATE, color.a);
}
"""
    }
}
