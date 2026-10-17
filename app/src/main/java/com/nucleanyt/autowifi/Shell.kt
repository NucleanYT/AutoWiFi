package com.nucleanyt.autowifi

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val TAG = "AutoWiFi"

data class ShellResult(val exitCode: Int, val output: String) {
    val ok get() = exitCode == 0
}

/** Runs a process, collecting stdout+stderr, and kills it if it takes longer than [timeoutMs]. */
fun runProcess(argv: List<String>, timeoutMs: Long): ShellResult {
    val process = ProcessBuilder(argv).redirectErrorStream(true).start()
    process.outputStream.close()
    var output = ""
    val reader = thread(name = "shell-reader") {
        output = try {
            process.inputStream.bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            ""
        }
    }
    if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
        process.destroy()
        reader.join(500)
        return ShellResult(-1, "Timed out: ${argv.joinToString(" ")}")
    }
    reader.join(2_000)
    return ShellResult(process.exitValue(), output)
}

enum class Backend(val label: String) {
    SHIZUKU("Shizuku"),
    ROOT("Root"),
    NONE("None"),
}

/** Runs shell commands with elevated rights, through Shizuku (preferred) or root. */
object Privileged {

    fun backend(context: Context): Backend = when {
        ShizukuShell.isReady() -> Backend.SHIZUKU
        Prefs(context).useRoot -> Backend.ROOT
        else -> Backend.NONE
    }

    /** Blocking — never call from the main thread. Returns null when no backend is available. */
    fun exec(context: Context, command: String): ShellResult? {
        val result = when (backend(context)) {
            Backend.SHIZUKU -> ShizukuShell.exec(command)
            Backend.ROOT -> RootShell.exec(command)
            Backend.NONE -> null
        }
        Log.d(TAG, "exec '$command' -> $result")
        return result
    }
}

object RootShell {

    fun exec(command: String, timeoutMs: Long = 10_000): ShellResult? = try {
        runProcess(listOf("su", "-c", command), timeoutMs)
    } catch (e: IOException) {
        null // no su binary
    }

    /** Blocking; may show the root manager's prompt. */
    fun isAvailable(): Boolean = exec("id", timeoutMs = 30_000)?.output?.contains("uid=0") == true
}

/**
 * Talks to [ShellService], which Shizuku runs as the `shell` user (the same rights `adb shell` has).
 */
object ShizukuShell {

    const val PERMISSION_REQUEST_CODE = 4242

    @Volatile
    private var binder: IBinder? = null

    @Volatile
    private var connectLatch: CountDownLatch? = null

    private val serviceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, ShellService::class.java.name))
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service != null && service.pingBinder()) binder = service
            connectLatch?.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
        }
    }

    /** Shizuku is installed, running and has granted us permission. */
    fun isReady(): Boolean = try {
        Shizuku.pingBinder() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    fun isRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Throwable) {
        false
    }

    fun requestPermission() {
        try {
            if (Shizuku.pingBinder() && !Shizuku.isPreV11()) Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        } catch (e: Throwable) {
            Log.w(TAG, "Shizuku permission request failed", e)
        }
    }

    /** Blocking — never call from the main thread. */
    fun exec(command: String): ShellResult? {
        if (!isReady()) return null
        val service = binder?.takeIf { it.pingBinder() } ?: connect() ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ShellService.DESCRIPTOR)
            data.writeString(command)
            service.transact(ShellService.TRANSACTION_EXEC, data, reply, 0)
            reply.readException()
            ShellResult(reply.readInt(), reply.readString() ?: "")
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku exec failed", e)
            binder = null
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    @Synchronized
    private fun connect(): IBinder? {
        binder?.takeIf { it.pingBinder() }?.let { return it }
        val latch = CountDownLatch(1)
        connectLatch = latch
        Handler(Looper.getMainLooper()).post {
            try {
                Shizuku.bindUserService(serviceArgs, connection)
            } catch (e: Throwable) {
                Log.w(TAG, "Shizuku bindUserService failed", e)
                latch.countDown()
            }
        }
        latch.await(10, TimeUnit.SECONDS)
        return binder
    }
}
