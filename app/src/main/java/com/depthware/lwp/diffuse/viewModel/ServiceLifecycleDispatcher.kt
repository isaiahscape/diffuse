package com.depthware.lwp.diffuse.viewModel

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

class ServiceLifecycleDispatcher(owner: LifecycleOwner) {

    private val registry = LifecycleRegistry(owner)
    private val handler = Handler(Looper.getMainLooper())
    private var lastDispatchRunnable: DispatchRunnable? = null

    private class DispatchRunnable(
        private val registry: LifecycleRegistry,
        private val event: Lifecycle.Event
    ) : Runnable {
        private var wasExecuted = false

        override fun run() {
            if (!wasExecuted) {
                registry.handleLifecycleEvent(event)
                wasExecuted = true
            }
        }
    }

    private fun postDispatchRunnable(event: Lifecycle.Event) {
        lastDispatchRunnable?.run()
        val runnable = DispatchRunnable(registry, event)
        lastDispatchRunnable = runnable
        handler.postAtFrontOfQueue(runnable)
    }

    val lifecycle: Lifecycle
        get() = registry

    fun onServicePreSuperOnCreate() {
        postDispatchRunnable(Lifecycle.Event.ON_CREATE)
        postDispatchRunnable(Lifecycle.Event.ON_START)
    }

    fun onServicePreSuperOnDestroy() {
        postDispatchRunnable(Lifecycle.Event.ON_STOP)
        postDispatchRunnable(Lifecycle.Event.ON_DESTROY)
    }
}
