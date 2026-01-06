package com.github.spwplace.spweeboard

import android.app.Application
import android.util.Log
import com.github.spwplace.spweeboard.model.InferenceManager
import uniffi.spweeboard_core.SpwGroundStore

/**
 * Main application class for spweebo'ard.
 */
class SpweeboardApplication : Application() {

    companion object {
        private const val TAG = "SpweeboardApplication"

        @Volatile
        private var groundStore: SpwGroundStore? = null

        init {
            // Load native library
            try {
                System.loadLibrary("spweeboard_core")
                Log.i(TAG, "Native library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library", e)
            }
        }

        /**
         * Returns the shared ground store instance.
         * Call [initGroundStore] first from the Application.
         */
        fun getGroundStore(): SpwGroundStore? = groundStore
    }

    override fun onCreate() {
        super.onCreate()

        // Initialize the inference engine (creates Rust engine instance)
        InferenceManager.initialize()
        Log.i(TAG, "InferenceManager initialized")

        // Initialize ground store (shared between app and keyboard)
        initGroundStore()
    }

    private fun initGroundStore() {
        try {
            val dbPath = filesDir.resolve("grounds.db").absolutePath
            groundStore = SpwGroundStore.open(dbPath)
            Log.i(TAG, "GroundStore initialized at $dbPath")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize GroundStore", e)
        }
    }
}
