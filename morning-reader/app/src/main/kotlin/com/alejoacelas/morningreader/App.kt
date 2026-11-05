package com.alejoacelas.morningreader

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Telemetry.init(this)
        Store.init(this)
        Blogs.schedule(this)
        Eink.offerAsHome(this)
    }
}
