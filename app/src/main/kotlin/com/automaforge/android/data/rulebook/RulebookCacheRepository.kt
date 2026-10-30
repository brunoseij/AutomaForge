package com.automaforge.android.data.rulebook

import android.util.AtomicFile
import com.automaforge.android.core.*
import com.google.gson.Gson
import java.io.File
import java.security.MessageDigest

/**
 * Same private-file persistence strategy as GameStore. Validated corrections live outside versioned
 * caches.
 */
class RulebookCacheRepository(private val root: File) {
    val gson = Gson()

    init {
        check(root.isDirectory || root.mkdirs())
    }

    fun directory(id: String): File {
        require(id.matches(Regex("[a-f0-9]{64}")))
        return File(root, id).also { check(it.isDirectory || it.mkdirs()) }
    }

    fun versionDir(id: String) =
        File(directory(id), "pipeline-$PIPELINE_VERSION-schema-$RULE_SCHEMA_VERSION").also {
            it.mkdirs()
        }

    fun original(id: String) = File(directory(id), "original")

    @Synchronized
    fun write(file: File, value: Any) {
        file.parentFile!!.mkdirs()
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(gson.toJson(value).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (t: Throwable) {
            atomic.failWrite(stream)
            throw t
        }
    }

    @Synchronized
    fun <T> read(file: File, type: Class<T>): T? {
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        return try {
            AtomicFile(file).openRead().bufferedReader().use { gson.fromJson(it, type) }
        } catch (e: Exception) {
            file.renameTo(File(file.path + ".invalid"))
            if (file.parentFile?.name == "responses")
                write(
                    File(file.parentFile, file.nameWithoutExtension + ".pending"),
                    mapOf("reason" to "damaged_response_cache"),
                )
            android.util.Log.w(
                "AutomaForgePdf",
                "Cache inválido; checkpoint será reconstruído ou exigirá confirmação.",
            )
            null
        }
    }

    fun page(id: String, number: Int) = File(versionDir(id), "page-$number.json")

    fun document(id: String) = File(versionDir(id), "document.json")

    fun response(key: String) = File(root, "responses/$key.json")

    fun pending(key: String) = File(root, "responses/$key.pending")

    fun validated(id: String, value: String) =
        write(
            File(directory(id), "human-validated.json"),
            mapOf(
                "schemaVersion" to RULE_SCHEMA_VERSION,
                "content" to value,
                "savedAt" to GameJson.now(),
            ),
        )

    @Synchronized
    fun delete(id: String) {
        directory(id).deleteRecursively()
    }

    fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val b = ByteArray(65536)
            while (true) {
                val n = input.read(b)
                if (n < 0) break
                digest.update(b, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") {
            "%02x".format(it)
        }

    fun preserve(file: File, id: String) {
        val target = original(id)
        if (!target.exists()) {
            val tmp = File(target.parentFile, "original.tmp")
            file.inputStream().use { i -> tmp.outputStream().use { i.copyTo(it) } }
            check(tmp.renameTo(target))
        }
    }
}
