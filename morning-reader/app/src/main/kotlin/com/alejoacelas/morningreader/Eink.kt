package com.alejoacelas.morningreader

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * On an Onyx Boox the same APK runs simplified for e-ink: black on white, no videos, music,
 * secondary material or feature requests, and it can be the home screen.
 */
object Eink {
    val on = Build.MANUFACTURER.equals("ONYX", ignoreCase = true)

    /** Counts Home presses while the app is the home screen; each one returns to Today. */
    val homePresses = MutableStateFlow(0)

    private const val BOOX_HOME = "com.onyx/.StartupActivity"

    /** Enables the home-screen alias on the Boox only, so the phone never lists the app as a launcher. */
    fun offerAsHome(context: Context) {
        if (!on) return
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, "${context.packageName}.Home"),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP,
        )
    }

    /** Opens Boox's own launcher, for the library, other apps and settings. */
    fun openBooxHome(context: Context) {
        context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName.unflattenFromString(BOOX_HOME)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
