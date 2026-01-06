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
        private val lock = Any()

        @Volatile
        private var groundStore: SpwGroundStore? = null

        @Volatile
        private var appInstance: SpweeboardApplication? = null

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
         * Thread-safe with double-checked locking.
         */
        fun getGroundStore(): SpwGroundStore? {
            // Fast path: already initialized
            groundStore?.let { return it }

            // Slow path: initialize with lock
            synchronized(lock) {
                // Double-check after acquiring lock
                groundStore?.let { return it }

                // Initialize if we have the app instance
                appInstance?.let { app ->
                    try {
                        val dbPath = app.filesDir.resolve("grounds.db").absolutePath
                        groundStore = SpwGroundStore.open(dbPath)
                        Log.i(TAG, "GroundStore initialized at $dbPath")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to initialize GroundStore", e)
                    }
                }
                return groundStore
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Store app instance for lazy initialization
        appInstance = this

        // Initialize the inference engine (creates Rust engine instance)
        InferenceManager.initialize()
        Log.i(TAG, "InferenceManager initialized")

        // Eagerly initialize ground store (shared between app and keyboard)
        getGroundStore()
    }
}
