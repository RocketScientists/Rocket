package org.mozilla.rocket.widget

import android.content.Context
import android.os.Bundle
import androidx.annotation.StyleRes
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.android.material.bottomsheet.BottomSheetDialog

open class LifecycleBottomSheetDialog :
    BottomSheetDialog, LifecycleOwner, DefaultLifecycleObserver {

    private val lifecycleRegistry = LifecycleRegistry(this)

    constructor(context: Context) : super(context)
    constructor(context: Context, @StyleRes theme: Int) : super(context, theme)

    override fun getLifecycle(): Lifecycle = lifecycleRegistry

    // Needs to be called when the dialog no longer needed
    fun release() {
        cleanUp()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super<BottomSheetDialog>.onCreate(savedInstanceState)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onStart() {
        super<BottomSheetDialog>.onStart()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    override fun onStop() {
        super<BottomSheetDialog>.onStop()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    // onActivityResume
    override fun onResume(owner: LifecycleOwner) {
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
    }

    // onActivityPause
    override fun onPause(owner: LifecycleOwner) {
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        }
    }

    // onActivityDestroy
    override fun onDestroy(owner: LifecycleOwner) {
        cleanUp()
    }

    private fun cleanUp() {
        if (isShowing) {
            dismiss()
        }
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.CREATED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
    }
}
