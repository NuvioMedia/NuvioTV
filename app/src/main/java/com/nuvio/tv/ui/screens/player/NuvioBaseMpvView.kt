package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import `is`.xyz.mpv.MPV

abstract class NuvioBaseMpvView(context: Context, attrs: AttributeSet?) : SurfaceView(context, attrs),
    SurfaceHolder.Callback {

    private var mpvInstance: MPV? = null

    val mpv: MPV
        get() = mpvInstance ?: MPV(context).also { mpvInstance = it }

    private var filePath: String? = null

    private var voInUse: String = "gpu"

    fun initialize(configDir: String, cacheDir: String) {
        mpv.setConfigDir(configDir)
        mpv.setCacheDir(cacheDir)
        initOptions()
        postInitOptions()
        mpv.setOptionString("force-window", "no")
        mpv.setOptionString("idle", "once")
        holder.addCallback(this)
        observeProperties()
    }

    fun destroy() {
        holder.removeCallback(this)
        mpvInstance?.close()
        mpvInstance = null
    }

    protected abstract fun initOptions()
    protected abstract fun postInitOptions()
    protected abstract fun observeProperties()

    fun playFile(filePath: String) {
        this.filePath = filePath
    }

    fun setVo(vo: String) {
        voInUse = vo
        mpv.setOptionString("vo", vo)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        mpv.setPropertyString("android-surface-size", "${width}x$height")
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.w(TAG, "attaching surface")
        mpv.attachSurface(holder.surface)
        mpv.setOptionString("force-window", "yes")

        val pendingFile = filePath
        if (pendingFile != null) {
            mpv.command("loadfile", pendingFile)
            filePath = null
        } else {
            mpv.setPropertyString("vo", voInUse)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.w(TAG, "detaching surface")
        mpv.setPropertyString("vo", "null")
        mpv.setPropertyString("force-window", "no")
        mpv.detachSurface()
    }

    companion object {
        private const val TAG = "mpv"
    }
}
