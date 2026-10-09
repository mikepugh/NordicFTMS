package com.nordicrower.app

import android.app.Application

class RowerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        RowerLog.initialize(this)
    }
}
