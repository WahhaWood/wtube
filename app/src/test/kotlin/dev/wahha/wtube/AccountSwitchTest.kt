package dev.wahha.wtube

import dev.wahha.wtube.data.WatchProgress
import dev.wahha.wtube.data.account.Account
import dev.wahha.wtube.data.account.Identity
import dev.wahha.wtube.data.account.Session
import dev.wahha.wtube.data.innertube.ResumeMark
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

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

/** Switching to another identity of the session: nothing changes unless YouTube confirmed the new one. */
class SwitchIdentityTest {
    private val start = Session(cookie = "SID=a", name = "Me", handle = "@me")
    private val brand = Identity("Brand", null, "brand.png", authUser = 0, pageId = "p1", selected = false)

    @After fun signOut() = runBlocking { Account.save(null) }

    @Test fun networkFailureKeepsTheOldIdentity() = runBlocking {
        Account.save(start)
        val failure = runCatching { Account.switchTo(brand) { throw IOException("offline") } }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(start, Account.session.value)
    }

    @Test fun rejectedIdentityKeepsTheOldOne() = runBlocking {
        Account.save(start)
        assertTrue(runCatching { Account.switchTo(brand) { null } }.isFailure)
        assertEquals(start, Account.session.value)
    }

    @Test fun confirmedIdentityIsSavedWithItsHandle() = runBlocking {
        Account.save(start)
        Account.switchTo(brand) { session ->
            assertEquals("p1", session.pageId)
            brand.copy(byline = "@brand", avatar = "new.png")
        }
        val now = Account.session.value!!
        assertEquals("p1", now.pageId)
        assertEquals("Brand", now.name)
        assertEquals("@brand", now.handle)
        assertEquals("new.png", now.avatar)
    }

    // Signed out (or switched elsewhere) while the check was running: the late answer is dropped.
    @Test fun lateAnswerAfterSignOutIsDropped() = runBlocking {
        Account.save(start)
        Account.switchTo(brand) { Account.save(null); brand }
        assertNull(Account.session.value)
    }
}
