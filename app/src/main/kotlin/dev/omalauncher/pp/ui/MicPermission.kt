package dev.omalauncher.pp.ui

import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Asks for what a widget's permission(...) or location("request") names, see
 * [dev.omalauncher.pp.widgets.runtime.WidgetPermissions]. Each activity that shows widgets has one in its [WidgetAsks], made
 * with it, as a launcher must be registered before the activity starts. (The microphone is [WidgetAsks]' own.)
 */
class MicPermission(
    private val activity: ComponentActivity,
) {
    private var then: ((Boolean) -> Unit)? = null

    private val launcher =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val then = then
            this.then = null
            then?.invoke(granted)
        }

    /** Asks for [permission]; `blocked` says Android won't show its dialog again, after two refusals, so only settings can grant it. */
    fun ask(
        permission: String,
        then: (granted: Boolean, blocked: Boolean) -> Unit,
    ) {
        this.then = { granted -> then(granted, !granted && !activity.shouldShowRequestPermissionRationale(permission)) }
        launcher.launch(permission)
    }
}
