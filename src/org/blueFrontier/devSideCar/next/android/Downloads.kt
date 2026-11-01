package org.blueFrontier.devSideCar.next.android

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The app's download queue. Each transfer runs on its own thread through the
 * kernel proxy, so it can be paused (the connection is dropped and the bytes
 * already written are kept) and resumed with an HTTP Range request.
 *
 * DownloadManager is not an option here: the app proxy only covers the WebView,
 * so a manager download would leave the kernel and fail on any host that needs
 * it.
 */
object Downloads {

    enum class State { RUNNING, PAUSED, DONE, FAILED }

    class Task(
        val url: String,
        val name: String,
        val mime: String,
        val userAgent: String,
    ) {
        @Volatile var state = State.RUNNING
        @Volatile var bytes = 0L
        @Volatile var total = -1L
        @Volatile var error: String? = null

        @Volatile internal var cancel = false
        @Volatile internal var conn: HttpURLConnection? = null
        internal var uri: Uri? = null
        internal var thread: Thread? = null

        /** 0-100, or -1 while the server has not said how large the file is. */
        val percent: Int get() = if (total > 0) ((bytes * 100) / total).toInt() else -1
    }

    val tasks = CopyOnWriteArrayList<Task>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    val active: Boolean get() = tasks.any { it.state == State.RUNNING }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun changed() {
        listeners.forEach { runCatching { it() } }
    }

    fun start(context: Context, url: String, userAgent: String, disposition: String?, mime: String?) {
        val task = Task(url, fileName(url, disposition), mime ?: "application/octet-stream", userAgent)
        tasks.add(0, task)
        changed()
        launch(context.applicationContext, task)
    }

    fun pause(task: Task) {
        if (task.state != State.RUNNING) return
        task.state = State.PAUSED
        // Dropping the socket is what actually stops the read loop; the catch
        // block sees PAUSED and leaves the bytes on disk alone.
        runCatching { task.conn?.disconnect() }
        changed()
    }

    fun resume(context: Context, task: Task) {
        if (task.state == State.RUNNING || task.state == State.DONE) return
        task.state = State.RUNNING
        task.error = null
        changed()
        launch(context.applicationContext, task)
    }

    fun cancel(context: Context, task: Task) {
        task.cancel = true
        runCatching { task.conn?.disconnect() }
        task.uri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
        tasks.remove(task)
        changed()
    }

    private fun launch(context: Context, task: Task) {
        task.thread = Thread {
            try {
                var offset = task.bytes
                val port = KernelRuntime.savedPort(context)
                val conn = java.net.URL(task.url).openConnection(
                    Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)),
                ) as HttpURLConnection
                task.conn = conn
                conn.setRequestProperty("User-Agent", task.userAgent)
                CookieManager.getInstance().getCookie(task.url)
                    ?.let { conn.setRequestProperty("Cookie", it) }
                if (offset > 0) conn.setRequestProperty("Range", "bytes=$offset-")
                conn.connectTimeout = 20_000
                conn.readTimeout = 60_000

                val code = conn.responseCode
                if (code !in 200..299) throw IllegalStateException("HTTP $code")
                // 206 means the server honoured the range; anything else means
                // it sent the whole file again, so restart from zero.
                val appending = offset > 0 && code == 206
                if (!appending) {
                    offset = 0
                    task.bytes = 0
                }
                task.total = if (appending) offset + conn.contentLengthLong else conn.contentLengthLong

                val uri = task.uri ?: insert(context, task).also { task.uri = it }
                var failed = false
                context.contentResolver.openOutputStream(uri, if (appending) "wa" else "wt")?.use { out ->
                    conn.inputStream.use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var lastNotify = 0L
                        loop@ while (true) {
                            if (task.cancel || task.state == State.PAUSED) break@loop
                            val read = input.read(buffer)
                            if (read < 0) break@loop
                            out.write(buffer, 0, read)
                            offset += read
                            task.bytes = offset
                            val now = System.currentTimeMillis()
                            if (now - lastNotify > 250) {
                                lastNotify = now
                                changed()
                            }
                        }
                    }
                } ?: throw IllegalStateException("无法写入下载目录")

                if (task.cancel) return@Thread
                if (task.state == State.PAUSED) return@Thread
                if (task.total > 0 && offset < task.total) {
                    // The socket closed early: leave it paused so the user can
                    // continue instead of starting over.
                    task.state = State.PAUSED
                    changed()
                    return@Thread
                }
                finish(context, task)
            } catch (t: Throwable) {
                if (task.cancel || task.state == State.PAUSED) return@Thread
                task.state = State.FAILED
                task.error = t.message ?: t.javaClass.simpleName
                changed()
            } finally {
                task.conn = null
            }
        }.also { it.start() }
    }

    private fun insert(context: Context, task: Task): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, task.name)
            put(MediaStore.MediaColumns.MIME_TYPE, task.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("无法在下载目录创建文件")
    }

    private fun finish(context: Context, task: Task) {
        val uri = task.uri ?: return
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        runCatching { context.contentResolver.update(uri, values, null, null) }
        task.state = State.DONE
        if (task.total <= 0) task.total = task.bytes
        changed()
    }

    private fun fileName(url: String, disposition: String?): String {
        val header = disposition ?: ""
        val utf8 = Regex("""filename\*\s*=\s*UTF-8''([^;]+)""", RegexOption.IGNORE_CASE)
            .find(header)?.groupValues?.get(1)
        val plain = Regex("""filename\s*=\s*"?([^";]+)"?""", RegexOption.IGNORE_CASE)
            .find(header)?.groupValues?.get(1)
        val fromUrl = url.substringAfterLast('/').substringBefore('?').takeIf { it.isNotBlank() }
        return (utf8 ?: plain ?: fromUrl ?: "download")
            .let { java.net.URLDecoder.decode(it, "UTF-8") }
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .take(120)
    }
}

