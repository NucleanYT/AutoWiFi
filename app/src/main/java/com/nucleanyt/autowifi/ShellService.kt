package com.nucleanyt.autowifi

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import kotlin.system.exitProcess

/**
 * Shizuku "user service": Shizuku starts this class in its own process running as the `shell`
 * user, so commands like `svc data enable` are allowed. It is not declared in the manifest.
 */
class ShellService : Binder() {

    init {
        attachInterface(null, DESCRIPTOR)
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = when (code) {
        TRANSACTION_EXEC -> {
            data.enforceInterface(DESCRIPTOR)
            val command = data.readString().orEmpty()
            val result = try {
                runProcess(listOf("sh", "-c", command), timeoutMs = 15_000)
            } catch (e: Exception) {
                ShellResult(-1, e.toString())
            }
            reply?.writeNoException()
            reply?.writeInt(result.exitCode)
            reply?.writeString(result.output)
            true
        }
        TRANSACTION_DESTROY -> exitProcess(0)
        else -> super.onTransact(code, data, reply, flags)
    }

    companion object {
        const val DESCRIPTOR = "com.nucleanyt.autowifi.IShellService"
        const val TRANSACTION_EXEC = IBinder.FIRST_CALL_TRANSACTION

        /** Sent by the Shizuku server when it removes the service (ShizukuApiConstants.USER_SERVICE_TRANSACTION_destroy). */
        const val TRANSACTION_DESTROY = 16777115
    }
}
