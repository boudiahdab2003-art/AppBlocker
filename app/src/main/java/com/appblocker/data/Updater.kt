package com.appblocker.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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

    /**
     * The four bytes every ZIP archive — and therefore every APK — starts with.
     *
     * Checked because "the download finished" and "the download is an app" are not the same
     * thing. A captive-portal Wi-Fi login page, or any proxy that answers 200 with its own HTML,
     * produces a complete, correctly-sized body that is not an APK; without this the installer
     * is handed that HTML and reports a parse error the user cannot interpret.
     */
    internal fun looksLikeApk(head: ByteArray): Boolean =
        head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
            head[2] == 3.toByte() && head[3] == 4.toByte()

    private fun looksLikeApk(file: File): Boolean = runCatching {
        file.inputStream().use { stream ->
            val buf = ByteArray(4)
            var filled = 0
            while (filled < 4) {
                val n = stream.read(buf, filled, 4 - filled)
                if (n <= 0) break
                filled += n
            }
            if (filled < 4) ByteArray(0) else buf
        }
    }.map(::looksLikeApk).getOrDefault(false)

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
                // Not every failure arrives as an exception: a proxy or captive portal can answer
                // 200-with-HTML, and some error responses carry a readable body rather than
                // throwing on getInputStream(). Insist on a plain OK before believing the bytes.
                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    error("server answered ${conn.responseCode}")
                }
                // contentLengthLong, not contentLength: the Int version overflows past 2GB and is
                // deprecated. -1 (no Content-Length) is normal for a chunked response.
                val total = conn.contentLengthLong
                var done = 0L
                conn.inputStream.use { input ->
                    dest.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            // Cancelling the download has to interrupt the copy, or "Cancel"
                            // would tear the dialog down while the transfer ran on regardless.
                            ensureActive()
                            output.write(buf, 0, read)
                            done += read
                            if (total > 0) onProgress(((done * 100) / total).toInt())
                        }
                    }
                }
                // A truncated download would install as a corrupt APK — fail instead.
                if (total > 0 && done < total) error("incomplete download ($done/$total bytes)")
                // The length check above is skipped entirely when the server sends no length, so
                // this is the only integrity check that always runs. Cheap, and it catches both
                // the wrong-content case and a download truncated at the very first buffer.
                if (!looksLikeApk(dest)) error("downloaded file is not an app")
                onProgress(100)
                dest
            } catch (e: Exception) {
                runCatching { dest.delete() } // don't leave a partial/corrupt APK behind
                throw e
            }
        }

    /**
     * True once the user has allowed this app to install packages.
     *
     * `canRequestPackageInstalls` is API 26; minSdk is 24. Calling it on 24/25 is a crash, not a
     * false — and on those versions the permission is a single global "unknown sources" setting
     * with no per-app screen to send anyone to, so the honest answer there is "nothing to ask for".
     */
    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            context.packageManager.canRequestPackageInstalls()
        else true

    /**
     * Opens the system "allow install unknown apps" screen for this app. Returns false if the
     * phone has no such screen — some OEM builds don't — so the caller can say so instead of
     * crashing on an unhandled intent.
     */
    fun requestInstallPermission(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.isSuccess

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

    /** Deletes a downloaded APK that is no longer needed. Both possible locations, because which
     *  one [download] used depends on whether external storage was mounted at the time. */
    fun discardDownload(context: Context) {
        listOfNotNull(context.getExternalFilesDir(null), context.filesDir).forEach { dir ->
            runCatching { File(dir, "update.apk").delete() }
        }
    }
}
