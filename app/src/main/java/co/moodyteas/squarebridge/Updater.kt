package co.moodyteas.squarebridge

import android.content.Intent
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.URL

/**
 * Self-update: checks a static JSON manifest ({"versionCode": n, "url": "..."})
 * for a newer build, downloads it, and offers a one-tap install. Publish each
 * CI build to the manifest URL and tablets pick up new versions on their own,
 * with no USB and no per-device pushing. Not silent (that needs device-owner
 * provisioning), so the register still confirms with one tap.
 * Set UPDATE_MANIFEST_URL at build time; left empty, the updater does nothing.
 */
object Updater {
    private const val TAG = "SquareBridge"
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L // don't nag more than every 6h

    fun check(activity: AppCompatActivity) {
        val manifestUrl = BuildConfig.UPDATE_MANIFEST_URL
        if (manifestUrl.isEmpty()) return
        val prefs = activity.getSharedPreferences("updater", 0)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("lastCheck", 0) < CHECK_INTERVAL_MS) return
        prefs.edit().putLong("lastCheck", now).apply()

        Thread {
            try {
                val manifest = JSONObject(URL(manifestUrl).readText())
                val remote = manifest.getInt("versionCode")
                if (remote <= BuildConfig.VERSION_CODE) return@Thread
                val url = manifest.getString("url")

                val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { if (it.name != "update-v$remote.apk") it.delete() }
                val apk = File(dir, "update-v$remote.apk")
                if (!apk.exists() || apk.length() == 0L) {
                    val tmp = File(dir, "update-v$remote.apk.part")
                    URL(url).openStream().use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    }
                    tmp.renameTo(apk)
                }
                Log.w(TAG, "update v$remote downloaded (${apk.length()} bytes)")
                activity.runOnUiThread {
                    if (!activity.isFinishing) promptInstall(activity, apk, remote)
                }
            } catch (e: Exception) {
                Log.w(TAG, "update check failed: ${e.message}")
            }
        }.start()
    }

    private fun promptInstall(activity: AppCompatActivity, apk: File, version: Int) {
        AlertDialog.Builder(activity)
            .setTitle("POS Update v$version")
            .setMessage(
                "A new version of the register app is ready.\n\n" +
                "Installing takes about 15 seconds, so do it between customers. " +
                "The app reopens by itself; if Android asks to allow installs " +
                "from this app, allow it (one-time)."
            )
            .setPositiveButton("Install now") { _, _ ->
                try {
                    val uri = FileProvider.getUriForFile(
                        activity, activity.packageName + ".fileprovider", apk
                    )
                    val intent = Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_ACTIVITY_NEW_TASK
                        )
                    activity.startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "install launch failed: ${e.message}")
                }
            }
            .setNegativeButton("Later", null)
            .show()
    }
}
