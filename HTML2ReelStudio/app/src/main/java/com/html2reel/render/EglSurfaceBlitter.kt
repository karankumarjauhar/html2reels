package com.html2reel.render

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Thread-affine OpenGL ES 2.0 renderer bound to MediaCodec's Input Surface.
 * Supports zero-copy OES external textures (from SurfaceTexture/VirtualDisplay)
 * as well as 2D Bitmap upload fallback, plus downscaled FBO thumbnail readback.
 */
class EglSurfaceBlitter(
    private val codecInputSurface: Surface,
    private val width: Int,
    private val height: Int
) {
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var oesProgram = 0
    private var tex2dProgram = 0
    private var oesTextureId = 0
    private var tex2dId = 0

    private var surfaceTexture: SurfaceTexture? = null
    var inputSurface: Surface? = null
        private set

    private val frameCounter = AtomicInteger(0)
    private val frameLock = Object()
    private var lastObservedSeq = 0
    private var hasReceivedFirstOesFrame = false
    private var lastPtsNs = -1L

    private var thumbFbo = 0
    private var thumbTex = 0
    private val thumbW: Int
    private val thumbH: Int
    private var thumbBuffer: ByteBuffer? = null

    private val oesMatrix = FloatArray(16)
    private val identityMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val flipYMatrix = FloatArray(16).also {
        Matrix.setIdentityM(it, 0)
        Matrix.translateM(it, 0, 0f, 1f, 0f)
        Matrix.scaleM(it, 0, 1f, -1f, 1f)
    }

    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(QUAD_COORDS.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(QUAD_COORDS)
            position(0)
        }

    private val released = AtomicBoolean(false)

    init {
        val ratio = height.toFloat() / width.coerceAtLeast(1).toFloat()
        thumbW = 270
        thumbH = (270f * ratio).toInt().coerceIn(120, 480)
        initEgl()
        initGlResources()
    }

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        require(eglDisplay != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }

        val version = IntArray(2)
        require(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        require(EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)) {
            "eglChooseConfig failed"
        }
        val config = configs[0] ?: error("No matching EGLConfig found")

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        require(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

        val surfAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, codecInputSurface, surfAttribs, 0)
        require(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }

        require(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            "eglMakeCurrent failed"
        }
    }

    private fun initGlResources() {
        oesProgram = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER_OES)
        tex2dProgram = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER_2D)

        val textures = IntArray(2)
        GLES20.glGenTextures(2, textures, 0)
        oesTextureId = textures[0]
        tex2dId = textures[1]

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex2dId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        surfaceTexture = SurfaceTexture(oesTextureId).apply {
            setDefaultBufferSize(width, height)
            setOnFrameAvailableListener {
                synchronized(frameLock) {
                    frameCounter.incrementAndGet()
                    frameLock.notifyAll()
                }
            }
        }
        inputSurface = Surface(surfaceTexture)

        // Small offscreen FBO for periodic thumbnail preview extraction.
        val fboIds = IntArray(1)
        val thumbTexIds = IntArray(1)
        GLES20.glGenFramebuffers(1, fboIds, 0)
        GLES20.glGenTextures(1, thumbTexIds, 0)
        thumbFbo = fboIds[0]
        thumbTex = thumbTexIds[0]

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, thumbTex)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            thumbW, thumbH, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, thumbFbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, thumbTex, 0
        )
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        thumbBuffer = ByteBuffer.allocateDirect(thumbW * thumbH * 4).order(ByteOrder.LITTLE_ENDIAN)
    }

    fun currentOesSeq(): Int = frameCounter.get()

    /**
     * Waits briefly for SurfaceFlinger to deliver a composited frame after JS 2x rAF settles.
     * Static frames do not emit a new buffer, so timeout falls through cleanly to reuse the bound texture.
     */
    fun awaitNewOesFrame(previousSeq: Int, timeoutMs: Long = 45L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(frameLock) {
            while (frameCounter.get() == previousSeq) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0L) return false
                frameLock.wait(remaining)
            }
            return true
        }
    }

    fun drawOesFrame(ptsNs: Long, captureThumbnail: Boolean): Bitmap? {
        if (released.get()) return null
        val st = surfaceTexture ?: return null
        val currentSeq = frameCounter.get()
        if (currentSeq != lastObservedSeq || !hasReceivedFirstOesFrame) {
            if (currentSeq > 0) {
                st.updateTexImage()
                st.getTransformMatrix(oesMatrix)
                lastObservedSeq = currentSeq
                hasReceivedFirstOesFrame = true
            } else {
                Matrix.setIdentityM(oesMatrix, 0)
            }
        }

        val thumb = if (captureThumbnail) {
            renderToThumbFbo(oesProgram, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId, oesMatrix)
        } else {
            null
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawQuad(oesProgram, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId, oesMatrix)
        swapWithMonotonicPts(ptsNs)
        return thumb
    }

    fun drawBitmapFrame(bitmap: Bitmap, ptsNs: Long, captureThumbnail: Boolean): Bitmap? {
        if (released.get()) return null
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex2dId)
        if (!bitmapUploadedOnce) {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            bitmapUploadedOnce = true
        } else {
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap)
        }

        val thumb = if (captureThumbnail) {
            renderToThumbFbo(tex2dProgram, GLES20.GL_TEXTURE_2D, tex2dId, flipYMatrix)
        } else {
            null
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawQuad(tex2dProgram, GLES20.GL_TEXTURE_2D, tex2dId, identityMatrix)
        swapWithMonotonicPts(ptsNs)
        return thumb
    }

    private var bitmapUploadedOnce = false

    private fun swapWithMonotonicPts(ptsNs: Long) {
        val safePtsNs = if (ptsNs <= lastPtsNs) lastPtsNs + 1L else ptsNs
        lastPtsNs = safePtsNs
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, safePtsNs)
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    private fun renderToThumbFbo(
        program: Int,
        texTarget: Int,
        texId: Int,
        matrix: FloatArray
    ): Bitmap? {
        val buf = thumbBuffer ?: return null
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, thumbFbo)
        GLES20.glViewport(0, 0, thumbW, thumbH)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawQuad(program, texTarget, texId, matrix)
        buf.rewind()
        GLES20.glReadPixels(0, 0, thumbW, thumbH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        buf.rewind()
        return Bitmap.createBitmap(thumbW, thumbH, Bitmap.Config.ARGB_8888).apply {
            copyPixelsFromBuffer(buf)
        }
    }

    private fun drawQuad(program: Int, texTarget: Int, texId: Int, texMatrix: FloatArray) {
        GLES20.glUseProgram(program)
        val posLoc = GLES20.glGetAttribLocation(program, "aPos")
        val uvLoc = GLES20.glGetAttribLocation(program, "aUv")
        val matLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")
        val texLoc = GLES20.glGetUniformLocation(program, "uTexture")

        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)

        vertexBuffer.position(2)
        GLES20.glEnableVertexAttribArray(uvLoc)
        GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)

        GLES20.glUniformMatrix4fv(matLoc, 1, false, texMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(texTarget, texId)
        GLES20.glUniform1i(texLoc, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(uvLoc)
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        runCatching { inputSurface?.release() }
        runCatching { surfaceTexture?.release() }
        inputSurface = null
        surfaceTexture = null

        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            runCatching {
                EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
                if (oesProgram != 0) GLES20.glDeleteProgram(oesProgram)
                if (tex2dProgram != 0) GLES20.glDeleteProgram(tex2dProgram)
                if (thumbFbo != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(thumbFbo), 0)
                GLES20.glDeleteTextures(3, intArrayOf(oesTextureId, tex2dId, thumbTex), 0)
            }
            runCatching {
                EGL14.eglMakeCurrent(
                    eglDisplay,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
                )
            }
            if (eglSurface != EGL14.EGL_NO_SURFACE) runCatching { EGL14.eglDestroySurface(eglDisplay, eglSurface) }
            if (eglContext != EGL14.EGL_NO_CONTEXT) runCatching { EGL14.eglDestroyContext(eglDisplay, eglContext) }
            runCatching { EGL14.eglTerminate(eglDisplay) }
        }
        eglSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        eglDisplay = EGL14.EGL_NO_DISPLAY
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val vShader = compileShader(GLES20.GL_VERTEX_SHADER, vs)
        val fShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fs)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vShader)
        GLES20.glAttachShader(prog, fShader)
        GLES20.glLinkProgram(prog)
        GLES20.glDeleteShader(vShader)
        GLES20.glDeleteShader(fShader)
        val status = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
        require(status[0] == GLES20.GL_TRUE) {
            "Shader link failed: ${GLES20.glGetProgramInfoLog(prog)}"
        }
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        require(status[0] == GLES20.GL_TRUE) {
            "Shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}"
        }
        return shader
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        // Fullscreen quad: (x, y, u, v)
        private val QUAD_COORDS = floatArrayOf(
            -1f, -1f, 0f, 0f,
             1f, -1f, 1f, 0f,
            -1f,  1f, 0f, 1f,
             1f,  1f, 1f, 1f
        )

        private const val VERTEX_SHADER = """
            attribute vec4 aPos;
            attribute vec4 aUv;
            uniform mat4 uTexMatrix;
            varying vec2 vUv;
            void main() {
                gl_Position = aPos;
                vUv = (uTexMatrix * vec4(aUv.xy, 0.0, 1.0)).xy;
            }
        """

        private const val FRAGMENT_SHADER_OES = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vUv;
            uniform samplerExternalOES uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vUv);
            }
        """

        private const val FRAGMENT_SHADER_2D = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uTexture;
            void main() {
                // Bitmap coordinates are top-down while GL textures are bottom-up.
                gl_FragColor = texture2D(uTexture, vec2(vUv.x, 1.0 - vUv.y));
            }
        """
    }
}
