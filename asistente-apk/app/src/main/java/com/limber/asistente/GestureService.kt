package com.limber.asistente

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Servicio aparte solo para tocar la pantalla. Con canPerformGestures prendido, Android mete un filtro
 * en la entrada y la cruceta y el stick dejan de llegar a los juegos; por eso se prende solo mientras se toca.
 */
class GestureService : AccessibilityService() {

    override fun onServiceConnected() { instance = this }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private suspend fun dispatch(path: Path, ms: Long): Boolean = suspendCancellableCoroutine { cont ->
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, ms)).build()
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) = cont.resume(true)
            override fun onCancelled(d: GestureDescription?) = cont.resume(false)
        }, null)
        if (!ok) cont.resume(false)
    }

    companion object {
        @Volatile var instance: GestureService? = null
        private const val KEY = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES

        private fun name(ctx: Context) = "${ctx.packageName}/${GestureService::class.java.name}"

        private fun enabled(ctx: Context): List<String> =
            (Settings.Secure.getString(ctx.contentResolver, KEY) ?: "").split(':').filter { it.isNotBlank() }

        /** Prende o apaga este servicio sin tocar los demás (necesita WRITE_SECURE_SETTINGS, concedido por adb). */
        fun setEnabled(ctx: Context, on: Boolean): Boolean = runCatching {
            val others = enabled(ctx).filterNot { it == name(ctx) }
            Settings.Secure.putString(ctx.contentResolver, KEY, (if (on) others + name(ctx) else others).joinToString(":"))
            Settings.Secure.putInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            true
        }.getOrElse { android.util.Log.w("Asistente", "No pude cambiar el servicio de toques", it); false }

        /** Toca o desliza: prende el servicio si hace falta y espera a que conecte. */
        suspend fun gesture(ctx: Context, path: Path, ms: Long): Boolean {
            if (instance == null && !setEnabled(ctx, true)) return false
            val svc = instance ?: withTimeoutOrNull(4_000) { while (instance == null) delay(100); instance } ?: return false
            return svc.dispatch(path, ms)
        }
    }
}
