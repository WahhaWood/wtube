package dev.wahha.wtube

import dev.wahha.wtube.data.WatchProgress
import dev.wahha.wtube.data.account.Account
import dev.wahha.wtube.data.account.Session
import dev.wahha.wtube.data.innertube.ResumeMark
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** The places the account loaded in its lists must not outlive the account. */
class AccountSwitchTest {
    private fun settle() = runBlocking { delay(200) }

    @After fun signOut() = runBlocking { Account.save(null) }

    @Test fun anotherAccountDoesNotInheritPlaces() = runBlocking {
        Account.save(Session(cookie = "SID=a"))
        settle()
        WatchProgress.record(mapOf("vid" to ResumeMark(startSeconds = 90, percent = 10)), emptyList())
        assertEquals(90_000L, WatchProgress.resumeMs("vid"))

        Account.save(Session(cookie = "SID=a", authUser = 1))
        settle()
        assertEquals(0L, WatchProgress.resumeMs("vid"))
    }

    @Test fun signingOutAndInAgainStartsClean() = runBlocking {
        Account.save(Session(cookie = "SID=a"))
        settle()
        WatchProgress.record(mapOf("vid" to ResumeMark(startSeconds = 90, percent = 10)), emptyList())
        Account.save(null)
        settle()
        Account.save(Session(cookie = "SID=b"))
        settle()
        assertEquals(0L, WatchProgress.resumeMs("vid"))
    }
}
