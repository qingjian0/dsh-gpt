package com.gpt.hook.diagnostics

import dalvik.system.BaseDexClassLoader
import dalvik.system.DexFile
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.LinkedHashSet

/** Full DEX class-name export for diagnostics; writes only to the target app's private files directory. */
object ClassScanner {
    fun scan(classLoader: ClassLoader, filesDir: File): Int {
        val started = System.nanoTime()
        val names = LinkedHashSet<String>()
        try {
            val base = classLoader as? BaseDexClassLoader
                ?: throw IllegalStateException("Unsupported class loader: ${classLoader.javaClass.name}")
            val pathListField = BaseDexClassLoader::class.java.getDeclaredField("pathList").apply {
                isAccessible = true
            }
            val pathList = pathListField.get(base)
                ?: throw IllegalStateException("ClassLoader pathList unavailable")
            val elementsField = pathList.javaClass.getDeclaredField("dexElements").apply {
                isAccessible = true
            }
            val elements = elementsField.get(pathList) as? Array<*>
                ?: throw IllegalStateException("DEX elements unavailable")

            for (element in elements) {
                if (element == null) continue
                val dexField = generateSequence(element.javaClass as Class<*>?) { it.superclass }
                    .mapNotNull { type -> runCatching { type.getDeclaredField("dexFile") }.getOrNull() }
                    .firstOrNull() ?: continue
                dexField.isAccessible = true
                val dex = runCatching { dexField.get(element) as? DexFile }.getOrNull() ?: continue
                val entries = runCatching { dex.entries() }.getOrNull() ?: continue
                while (entries.hasMoreElements()) names.add(entries.nextElement())
            }
        } catch (t: Throwable) {
            ModuleLogger.e("class scan failed; reason=${t.javaClass.simpleName}", t)
            return 0
        }

        val output = File(filesDir, "class-scan.txt")
        try {
            output.parentFile?.mkdirs()
            BufferedWriter(FileWriter(output, false), 64 * 1024).use { writer ->
                writer.append("# classes=${names.size}\n")
                names.forEach { writer.append(it).append('\n') }
            }
        } catch (t: Throwable) {
            ModuleLogger.e("class scan export failed; reason=${t.javaClass.simpleName}", t)
            return 0
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        ModuleLogger.i("class scan complete; classes=${names.size}; elapsedMs=$elapsedMs; file=${output.absolutePath}")
        return names.size
    }
}
