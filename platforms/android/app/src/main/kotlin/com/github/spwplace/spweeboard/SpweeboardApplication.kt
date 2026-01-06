package com.github.spwplace.spweeboard

import android.app.Application

/**
 * Main application class for spweebo'ard.
 */
class SpweeboardApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // TODO: Initialize Rust core via JNI/UniFFI
    }
}
