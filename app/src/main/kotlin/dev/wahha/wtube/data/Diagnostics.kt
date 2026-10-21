package dev.wahha.wtube.data

import dev.wahha.wtube.data.account.SessionExpiredException
import dev.wahha.wtube.data.innertube.ActionRejectedException
import dev.wahha.wtube.data.innertube.InnerTubeFormatException
import dev.wahha.wtube.data.innertube.InnerTubeHttpException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import java.io.IOException

/** What went wrong, for the log: tells a broken connection from a changed YouTube from a refused account. */
enum class ErrorKind(val label: String) {
    Network("network"),
    Auth("auth"),
    Antibot("antibot"),
    Restricted("restricted"),
    Parse("parse"),
    Http("http"),
    Rejected("rejected"),
    Other("other"),
}

/** Where the failing request came from: our own InnerTube client, the extractor, or neither. */
enum class ErrorSource(val label: String) { InnerTube("innertube"), Extractor("extractor"), App("app") }

private fun kindOf(e: Throwable): ErrorKind? = when (e) {
    is SessionExpiredException -> ErrorKind.Auth
    is ReCaptchaException, is SignInConfirmNotBotException -> ErrorKind.Antibot
    is ContentNotAvailableException -> ErrorKind.Restricted
    is ActionRejectedException -> ErrorKind.Rejected
    is InnerTubeFormatException, is ParsingException, is com.grack.nanojson.JsonParserException -> ErrorKind.Parse
    is InnerTubeHttpException -> ErrorKind.Http
    is IOException -> ErrorKind.Network
    else -> null
}

private fun sourceOf(e: Throwable): ErrorSource? = when {
    e is SessionExpiredException || e is ActionRejectedException || e is InnerTubeHttpException || e is InnerTubeFormatException -> ErrorSource.InnerTube
    e.javaClass.name.startsWith("org.schabi.newpipe.extractor") -> ErrorSource.Extractor
    else -> null
}

/** The first meaningful cause in the chain decides; an unknown exception (a bug of ours or a surprise shape) is [ErrorKind.Other]. */
fun errorKind(error: Throwable): ErrorKind = generateSequence(error) { it.cause }.firstNotNullOfOrNull(::kindOf) ?: ErrorKind.Other

fun errorSource(error: Throwable): ErrorSource = generateSequence(error) { it.cause }.firstNotNullOfOrNull(::sourceOf) ?: ErrorSource.App

/** `[source/kind]` for the head of a log line. */
fun errorTag(error: Throwable): String = "[${errorSource(error).label}/${errorKind(error).label}]"
