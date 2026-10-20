package dev.wahha.wtube.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The paths worth having compiled ahead of time: a cold start, search with its results list, and a video
 * opening and collapsing into the mini player. Needs the network, like the app itself.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = "dev.wahha.wtube", includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.desc("Поиск")), TIMEOUT_MS)

        device.findObject(By.desc("Поиск")).click()
        val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), TIMEOUT_MS)
        field.text = "lofi girl"
        device.pressEnter()
        // Gboard asks about the stylus on its first use.
        device.wait(Until.findObject(By.text("Cancel")), 1_000)?.click()
        device.wait(Until.hasObject(By.textContains("просмотров")), TIMEOUT_MS)
        device.pressBack() // the keyboard

        val list = device.findObject(By.scrollable(true))
        repeat(3) { list.fling(Direction.DOWN) }
        repeat(3) { list.fling(Direction.UP) }

        device.findObject(By.textContains("просмотров")).click()
        device.wait(Until.hasObject(By.text("Комментарии")), TIMEOUT_MS)
        device.waitForIdle()
        device.pressBack() // into the mini player
        device.waitForIdle()
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
    }
}
