package com.roychong.deskspirit.render

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet

/**
 * 音频驱动的 3D 渲染视图。外层只负责把音频帧推进渲染器。
 */
class SphereView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    private val sphereRenderer = SphereRenderer()

    init {
        setEGLContextClientVersion(3)
        setRenderer(sphereRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        preserveEGLContextOnPause = true
    }

    fun pushAudio(level: Float, bass: Float, mid: Float, treble: Float) {
        sphereRenderer.setFrame(level, bass, mid, treble)
    }
}
