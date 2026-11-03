package com.littlez.usbdisplay

import android.app.Application

class UsbDisplayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // as early as possible, so that a crash during start up is recorded too
        CrashLog.install(this)
    }
}
