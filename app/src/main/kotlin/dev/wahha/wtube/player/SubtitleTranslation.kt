package dev.wahha.wtube.player

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

private const val TAG = "WTubeSubtitles"
private const val BATCH = 200

private val Tags = Regex("<[^>]*>")

private fun String.unescapeVtt() = replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

private fun String.escapeVtt() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/**
 * [vtt] with every cue's text replaced by its translation: [translate] gets a batch of plain texts and
 * returns as many. Timings and the file's header stay; a cue becomes one line, and repeats (rolling
 * auto-generated captions) are translated once.
 */
internal fun translateVtt(vtt: String, translate: (List<String>) -> List<String>): String {
    val blocks = vtt.replace("\r\n", "\n").trim().split(Regex("\n{2,}")).map { it.lines() }
    fun textOf(lines: List<String>, at: Int) = lines.drop(at + 1).joinToString(" ") { it.replace(Tags, "").unescapeVtt().trim() }.trim()
    val texts = blocks.mapNotNull { lines ->
        lines.indexOfFirst { "-->" in it }.takeIf { it >= 0 }?.let { textOf(lines, it) }?.takeIf { it.isNotEmpty() }
    }.distinct()
    val translated = HashMap<String, String>()
    texts.chunked(BATCH).forEach { batch ->
        val result = translate(batch)
        require(result.size == batch.size) { "Translator returned ${result.size} of ${batch.size} texts" }
        batch.zip(result).forEach { (from, to) -> translated[from] = to }
    }
    return blocks.joinToString("\n\n", postfix = "\n") { lines ->
        val at = lines.indexOfFirst { "-->" in it }
        if (at < 0) return@joinToString lines.joinToString("\n")
        val text = textOf(lines, at)
        (lines.take(at + 1) + listOfNotNull(translated[text]?.escapeVtt())).joinToString("\n")
    }
}

/**
 * Translates [vtt] from [from] into [to] on the device with ML Kit: YouTube's own machine translation
 * needs a PO token we cannot get, and this needs no key or network once the language models (about
 * 30 MB each, fetched on first use) are in. Blocks, so only for a loader thread. Throws when a language is unsupported.
 */
internal fun translateOnDevice(vtt: String, from: String, to: String): String {
    val source = requireNotNull(TranslateLanguage.fromLanguageTag(from.substringBefore('-'))) { "No model for $from" }
    val target = requireNotNull(TranslateLanguage.fromLanguageTag(to.substringBefore('-'))) { "No model for $to" }
    val translator = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build())
    try {
        Log.i(TAG, "Translating captions $source -> $target")
        Tasks.await(translator.downloadModelIfNeeded(DownloadConditions.Builder().build()))
        return translateVtt(vtt) { batch ->
            Tasks.await(Tasks.whenAllSuccess<String>(batch.map(translator::translate)))
        }
    } finally {
        translator.close()
    }
}
