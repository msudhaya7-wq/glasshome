package com.liquidglass.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.provider.MediaStore

/** Phone-la install aana oru app. */
data class AppEntry(
    val label: String,
    val component: ComponentName,
    val icon: Drawable
) {
    val key: String get() = component.flattenToString()
}

object AppRepository {

    /** Launcher-la kaatta vendiya ella apps-um, A-Z order-la. */
    fun loadApps(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val infos = pm.queryIntentActivities(intent, 0)
        val self = context.packageName
        return infos
            .filter { it.activityInfo.packageName != self }
            .map {
                AppEntry(
                    label = it.loadLabel(pm).toString(),
                    component = ComponentName(it.activityInfo.packageName, it.activityInfo.name),
                    icon = IconShaper.shape(context, it.loadIcon(pm))
                )
            }
            .distinctBy { it.key }
            .sortedBy { it.label.lowercase() }
    }

    /** Dock-ku default apps: Phone, Messages, Browser, Camera. */
    fun defaultDock(context: Context, apps: List<AppEntry>): List<String> {
        val pm = context.packageManager
        val intents = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING),
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER),
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        )
        val result = mutableListOf<String>()
        for (i in intents) {
            val pkg = try {
                pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            } catch (e: Exception) {
                null
            } ?: continue
            if (pkg == "android") continue // "choose app" screen, real app illa
            val app = apps.firstOrNull { it.component.packageName == pkg } ?: continue
            if (app.key !in result) result.add(app.key)
        }
        // Ethavathu kidaikkalana, list-la first apps vechu fill pannuvom
        for (a in apps) {
            if (result.size >= 4) break
            if (a.key !in result) result.add(a.key)
        }
        return result.take(4)
    }
}

/** Ore icon-a pala idangalla (grid, dock, search) kaatta, ovvoru view-kum thani copy. */
fun AppEntry.freshIcon(): Drawable =
    icon.constantState?.newDrawable()?.mutate() ?: icon
