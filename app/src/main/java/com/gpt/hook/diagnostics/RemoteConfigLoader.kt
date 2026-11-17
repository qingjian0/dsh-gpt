package com.gpt.hook.diagnostics

import android.os.ParcelFileDescriptor
import io.github.libxposed.api.XposedInterface
import java.io.BufferedReader
import java.io.InputStreamReader

/** Reads a config placed in the framework-managed module shared-files directory. */
object RemoteConfigLoader {
    const val FILE_NAME = "dsh-hook-rules.json"

    fun read(framework: XposedInterface): String? {
        return try {
            ParcelFileDescriptor.AutoCloseInputStream(framework.openRemoteFile(FILE_NAME)).use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    val text = reader.readText()
                    require(text.length <= 128 * 1024) { "Remote config too large" }
                    text
                }
            }
        } catch (_: java.io.FileNotFoundException) {
            null
        } catch (t: Throwable) {
            ModuleLogger.w("remote config read failed; reason=${t.javaClass.simpleName}")
            null
        }
    }
}
