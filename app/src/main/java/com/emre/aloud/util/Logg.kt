package com.emre.aloud.util

import android.util.Log
import com.emre.aloud.BuildConfig

/** Log.d, compiled out of release builds along with the string concatenation. */
object Logg {
    private const val TAG = "Aloud"

    fun d(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }
}
