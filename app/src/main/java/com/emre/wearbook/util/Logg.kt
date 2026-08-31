package com.emre.wearbook.util

import android.util.Log
import com.emre.wearbook.BuildConfig

/** Log.d, compiled out of release builds along with the string concatenation. */
object Logg {
    private const val TAG = "WearBite"

    fun d(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }
}
