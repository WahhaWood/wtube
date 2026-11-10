package dev.wahha.wtube

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Plurals pick their form by the rules of the language the strings are in, for both interface languages. */
@RunWith(AndroidJUnit4::class)
class LanguageTest {
    private val resources get() = ApplicationProvider.getApplicationContext<Context>().resources

    private fun views(n: Int) = resources.getQuantityString(R.plurals.views, n, n.toString())

    @Test
    fun russianPlurals() {
        assertEquals(listOf("1 просмотр", "2 просмотра", "5 просмотров", "21 просмотр"), listOf(1, 2, 5, 21).map(::views))
    }

    @Test
    @Config(qualifiers = "en")
    fun englishPlurals() {
        assertEquals(listOf("1 view", "2 views", "5 views", "21 views"), listOf(1, 2, 5, 21).map(::views))
        assertEquals("Subscriptions", resources.getString(R.string.tab_subscriptions))
    }
}
