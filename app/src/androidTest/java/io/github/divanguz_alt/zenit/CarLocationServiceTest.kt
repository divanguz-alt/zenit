package io.github.divanguz_alt.zenit

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.divanguz_alt.zenit.car.CarLocationService
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The car location service really runs as a foreground service of type location, with the screen
 * off. (What this can't prove: the background-start exemption under a real Android Auto host.)
 */
@RunWith(AndroidJUnit4::class)
class CarLocationServiceTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val context = instr.targetContext

    private fun shell(cmd: String): String =
        instr.uiAutomation.executeShellCommand(cmd).use { pfd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readText()
        }

    @Test fun runsInForegroundWithLocationTypeWhileScreenIsOff() {
        shell("pm grant ${context.packageName} android.permission.ACCESS_FINE_LOCATION")
        CarLocationService.start(context)
        SystemClock.sleep(1_500)
        shell("input keyevent KEYCODE_SLEEP")
        SystemClock.sleep(2_000)
        val dump = shell("dumpsys activity services ${context.packageName}")
        val block = dump.substringAfter("CarLocationService", missingDelimiterValue = "")
        assertTrue("service not running:\n$dump", block.isNotEmpty())
        assertTrue("not foreground:\n$block", block.contains("isForeground=true"))
        // 0x8 = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        assertTrue("wrong type:\n$block", Regex("types=0x0*8\\b").containsMatchIn(block))
    }

    @After fun tearDown() {
        CarLocationService.stop(context)
        shell("input keyevent KEYCODE_WAKEUP")
    }
}
