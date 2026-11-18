package com.dastyab.app

import android.app.Application

/**
 * DastyabApplication — Global process bootstrap.
 *
 * Ensures logging, deadlock-safe crash capture, and command history are initialized
 * before ANY Activity, VpnService, Quick Settings Tile, Widget, or BroadcastReceiver runs.
 */
class DastyabApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        runCatching { DastyabLogger.init(filesDir, applicationContext) }
        runCatching { CrashHandler.install(applicationContext, filesDir) }
        runCatching { CommandHistory.init(filesDir) }
    }
}
