package com.github.spwplace.spweeboard

import android.app.Application
import android.util.Log
import com.github.spwplace.spweeboard.model.InferenceManager

/**
 * Main application class for spweebo'ard.
 */
class SpweeboardApplication : Application() {

    companion object {
        private const val TAG = "SpweeboardApplication"

        init {
            // Load native library
            try {
                System.loadLibrary("spweeboard_core")
                Log.i(TAG, "Native library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Initialize the inference engine (creates Rust engine instance)
        InferenceManager.initialize()
        Log.i(TAG, "InferenceManager initialized")
    }
}
