package dev.zmeyka.avpnp

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable

/**
 * The set of apps the operator assigns: everything launchable. Kept in one place so the UI, the
 * completeness check, and the compiler all agree on what "every app" means.
 */
object InstalledApps {

    data class Entry(val packageName: String, val label: String, val icon: Drawable)

    fun entries(context: Context): List<Entry> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return context.packageManager.queryIntentActivities(intent, 0)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .map { info ->
                Entry(
                    packageName = info.packageName,
                    label = context.packageManager.getApplicationLabel(info).toString(),
                    icon = info.loadIcon(context.packageManager),
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    fun packages(context: Context): List<String> = entries(context).map { it.packageName }

    fun labelOf(context: Context, packageName: String): String = runCatching {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)
}
