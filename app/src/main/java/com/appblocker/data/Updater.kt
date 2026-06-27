package com.appblocker.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pulls the latest release straight from the public GitHub repo and installs it in-app, so the user
 * never has to hunt down and sideload each APK by hand.
 */
object Updater {
    private const val API =
        "https://api.github.com/repos/boudiahdab2003-art/AppBlocker/releases/latest"

    data class Release(val version: String, val notes: String, val apkUrl: String)

    /** Fetches the latest release (version without the leading "v", notes, and .apk asset URL). */
    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        // Retry a couple of times to ride out transient network blips before giving up.
        repeat(2) {
            runCatching { fetchLatest() }.getOrNull()?.let { release -> return@withContext release }
        }
        null
    }

    private fun fetchLatest(): Release? {
        val conn = (URL(API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        return conn.inputStream.bufferedReader().use { it.readText() }.let { body ->
            val json = JSONObject(body)
            val version = json.getString("tag_name").removePrefix("v").trim()
            val notes = json.optString("body", "")
            val assets = json.getJSONArray("assets")
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk", ignoreCase = true)) {
                    apkUrl = a.getString("browser_download_url"); break
                }
            }
            apkUrl?.let { Release(version, notes, it) }
        }
    }

    /** True if [latest] is a higher dotted-int version than [current] (so 1.10 > 1.9). */
    fun isNewer(latest: String, current: String): Boolean {
        val l = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val c = current.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(l.size, c.size)) {
            val a = l.getOrElse(i) { 0 }; val b = c.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    val current: (Context) -> String get() = { ctx ->
        runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0.0"
        }.getOrDefault("0.0")
    }

    /** Downloads the APK to external files dir, reporting 0..100 progress. Returns the file. */
    suspend fun download(context: Context, url: String, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            // External files dir can be null (unmounted storage) — fall back to internal files.
            // Both are declared in res/xml/file_paths.xml so FileProvider can serve either.
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val dest = File(dir, "update.apk")
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                }
                conn.connect()
                val total = conn.contentLength.toLong()
                var done = 0L
                conn.inputStream.use { input ->
                    dest.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            output.write(buf, 0, read)
                            done += read
                            if (total > 0) onProgress(((done * 100) / total).toInt())
                        }
                    }
                }
                // A truncated download would install as a corrupt APK — fail instead.
                if (total > 0 && done < total) error("incomplete download ($done/$total bytes)")
                onProgress(100)
                dest
            } catch (e: Exception) {
                runCatching { dest.delete() } // don't leave a partial/corrupt APK behind
                throw e
            }
        }

    /** True once the user has allowed this app to install packages. */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens the system "allow install unknown apps" screen for this app. */
    fun requestInstallPermission(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Launches the system installer for the downloaded APK. */
    fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}
