package dev.wahha.wtube.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * The small JSON file behind a store ([History], [SearchHistory], [Hidden], downloads). It is read once,
 * then written by a single writer that starts only after the read: a change made while loading cannot
 * overwrite what was on disk, and writes cannot land out of order. [save] only marks the file dirty; the
 * writer takes [current] at write time, so a burst of changes costs one write of the latest state.
 */
internal class JsonFile<T>(private val tag: String, private val serializer: KSerializer<T>, private val current: () -> T) {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dirty = Channel<Unit>(Channel.CONFLATED)

    /** Reads [file] off the main thread, hands its contents (null if missing or unreadable) to [onLoaded], then starts saving. */
    fun load(file: File, onLoaded: (T?) -> Unit) {
        scope.launch {
            file.parentFile?.listFiles { f -> f.name.startsWith(file.name + ".") && f.name.endsWith(".tmp") }?.forEach { it.delete() }
            val fromFile = runCatching { if (file.exists()) json.decodeFromString(serializer, file.readText()) else null }
                .onFailure { Log.e(tag, "Unreadable ${file.name}, starting fresh", it) }
                .getOrNull()
            onLoaded(fromFile)
            dirty.consumeEach { write(file) }
        }
    }

    fun save() {
        dirty.trySend(Unit)
    }

    private suspend fun write(file: File) {
        repeat(RETRIES) { attempt ->
            val failure = runCatching { writeAtomically(file, json.encodeToString(serializer, current())) }.exceptionOrNull() ?: return
            Log.e(tag, "Could not save ${file.name} (attempt ${attempt + 1})", failure)
            delay(1_000L shl attempt)
        }
    }

    private fun writeAtomically(file: File, text: String) {
        val tmp = File.createTempFile(file.name + ".", ".tmp", file.parentFile)
        try {
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray())
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) throw IOException("Could not move ${tmp.name} over ${file.name}")
        } finally {
            tmp.delete()
        }
    }

    private companion object {
        const val RETRIES = 3
    }
}
