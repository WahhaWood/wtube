package dev.wahha.wtube.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.wahha.wtube.R
import dev.wahha.wtube.data.account.SessionExpiredException
import dev.wahha.wtube.data.errorTag
import dev.wahha.wtube.data.innertube.ActionRejectedException
import dev.wahha.wtube.data.innertube.InnerTubeHttpException
import dev.wahha.wtube.ui.icon.Error
import dev.wahha.wtube.ui.icon.WIcons
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException
import java.io.IOException

/** A user-facing reason for [error], looking through wrapped causes for the most specific one. */
fun errorMessage(error: Throwable): Int {
    for (e in generateSequence(error) { it.cause }) {
        when (e) {
            is SessionExpiredException -> return R.string.error_session
            is ActionRejectedException -> return R.string.error_rejected
            is AgeRestrictedContentException -> return R.string.error_age
            is GeographicRestrictionException -> return R.string.error_geo
            is PaidContentException, is YoutubeMusicPremiumContentException -> return R.string.error_paid
            is PrivateContentException -> return R.string.error_private
            is SignInConfirmNotBotException -> return R.string.error_bot
            is ContentNotAvailableException -> return R.string.error_unavailable
            is ReCaptchaException -> return R.string.error_captcha
            is InnerTubeHttpException -> return R.string.error_http
            is IOException -> return R.string.error_network
        }
    }
    return R.string.error_extractor
}

/**
 * The technical reason behind [error] for a bug report: class and message of each wrapped cause. Our
 * exceptions never carry cookies, and OkHttp leaves the values of Cookie and Authorization out of its own.
 */
fun errorDetail(error: Throwable): String =
    errorTag(error) + " " + generateSequence(error) { it.cause }.take(3)
        .joinToString(" ← ") { e -> e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "") }
        .take(300)

@Composable
fun ErrorState(error: Throwable, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    MessageState(WIcons.Error, stringResource(errorMessage(error)), modifier, detail = errorDetail(error)) {
        FilledTonalButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
    }
}

@Composable
fun MessageState(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    detail: String? = null,
    action: @Composable () -> Unit = {},
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
        if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        if (detail != null) {
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center)
        }
        action()
    }
}

/** Footer row of a paged list: a spinner while loading, a retry when the next page failed. */
@Composable
fun ListFooter(loading: Boolean, error: Throwable?, onRetry: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        when {
            error != null -> ErrorState(error, onRetry)
            loading -> CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
        }
    }
}

fun Modifier.shimmer(): Modifier = composed {
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val highlight = MaterialTheme.colorScheme.surfaceContainerHighest
    val progress by rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmer",
    )
    background(base).drawWithContent {
        val x = size.width * progress
        drawRect(
            Brush.linearGradient(
                listOf(Color.Transparent, highlight, Color.Transparent),
                start = Offset(x - size.width / 2, 0f),
                end = Offset(x + size.width / 2, size.height),
            ),
        )
        drawContent()
    }
}

/** Skeleton of the feed while the first page loads. */
@Composable
fun FeedSkeleton(modifier: Modifier = Modifier, compact: Boolean = false) {
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        repeat(4) {
            if (compact) {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(160.dp, 90.dp).shimmerShape(RoundedCornerShape(12.dp)))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SkeletonLine(1f)
                        SkeletonLine(0.6f)
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).shimmer())
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(36.dp).shimmerShape(CircleShape))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SkeletonLine(0.9f)
                            SkeletonLine(0.5f)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SkeletonLine(fraction: Float) {
    Spacer(Modifier.fillMaxWidth(fraction).height(12.dp).shimmerShape(RoundedCornerShape(6.dp)))
}

private fun Modifier.shimmerShape(shape: Shape) = clip(shape).shimmer()
