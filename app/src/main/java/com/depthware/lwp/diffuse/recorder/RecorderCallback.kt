package com.depthware.lwp.diffuse.recorder

interface RecorderCallback {
    fun onRecordSuccess(videoPath: String, coverPath: String?, durationMs: Long)
    fun onRecordError(error: Throwable, durationMs: Long)
    fun onRecordProgress(elapsedMs: Long)
}
