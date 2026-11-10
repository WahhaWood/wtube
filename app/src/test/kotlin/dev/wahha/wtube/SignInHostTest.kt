package dev.wahha.wtube

import dev.wahha.wtube.ui.account.isSignInHost
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignInHostTest {
    // A real sign-in from a Ukrainian SIM went through accounts.google.com.ua; sending it to the browser broke it.
    @Test
    fun googleSignInStaysInThePage() {
        for (host in listOf(
            "accounts.google.com", "accounts.youtube.com", "www.youtube.com", "ssl.gstatic.com",
            "accounts.google.com.ua", "accounts.google.ru", "accounts.google.co.uk", "www.google.de", "google.com.br",
        )) assertTrue(host, isSignInHost(host))
    }

    @Test
    fun otherSitesGoToTheBrowser() {
        for (host in listOf(
            null, "", "example.com", "notgoogle.com", "google.com.evil.example", "google.evil", "google.co.evil",
            "evil-google.com.ua", "google.example.ua", "youtube.com.ua",
        )) assertFalse("$host", isSignInHost(host))
    }
}
