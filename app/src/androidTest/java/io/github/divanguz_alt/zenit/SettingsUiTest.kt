package io.github.divanguz_alt.zenit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.divanguz_alt.zenit.settings.AppSettings
import io.github.divanguz_alt.zenit.settings.CoordinateFormat
import io.github.divanguz_alt.zenit.settings.GlobeStyle
import io.github.divanguz_alt.zenit.settings.SpeedUnit
import io.github.divanguz_alt.zenit.ui.Format
import io.github.divanguz_alt.zenit.ui.components.Metric
import io.github.divanguz_alt.zenit.ui.components.SettingsContent
import io.github.divanguz_alt.zenit.ui.theme.ZenitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsUiTest {
    @get:Rule val rule = createComposeRule()

    private var settings by mutableStateOf(AppSettings())
    private var debugOpened = false

    private fun setUp() {
        rule.setContent {
            ZenitTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SettingsContent(
                        settings = settings,
                        onChange = { settings = it(settings) },
                        orbitDataAge = "2 h ago",
                        orbitMessage = null,
                        refreshingOrbits = false,
                        onRefreshOrbits = {},
                        versionName = "1.0.0",
                        updateAvailable = false,
                        onVersionLongPress = { debugOpened = true },
                        onOpenGithub = {},
                        onCheckUpdates = {},
                        onLicenses = {},
                    )
                    // A live metric bound to the selected speed unit, like the dashboard.
                    Metric("Speed", Format.fixed(Format.speed(10.0, settings.speedUnit), 1), unit = settings.speedUnit.label)
                }
            }
        }
    }

    @Test fun switchingSpeedUnitUpdatesSettingsAndValue() {
        setUp()
        rule.onNodeWithTag("metric:Speed").assertTextContains("36.0").assertTextContains("km/h")
        rule.onNodeWithTag("choice:Speed:mph").performScrollTo().performClick()
        assertEquals(SpeedUnit.MPH, settings.speedUnit)
        rule.onNodeWithTag("choice:Speed:mph").assertIsSelected()
        rule.onNodeWithTag("metric:Speed").assertTextContains("22.4").assertTextContains("mph")
        rule.onNodeWithTag("choice:Speed:kn").performScrollTo().performClick()
        rule.onNodeWithTag("metric:Speed").assertTextContains("19.4").assertTextContains("kn")
    }

    @Test fun coordinateFormatAndGlobeStyle() {
        setUp()
        rule.onNodeWithTag("choice:Coordinates:DMS").performScrollTo().performClick()
        assertEquals(CoordinateFormat.DMS, settings.coordinateFormat)
        rule.onNodeWithTag("style:Realistic").performScrollTo().performClick()
        assertEquals(GlobeStyle.REALISTIC, settings.globeStyle)
        rule.onNodeWithTag("style:Realistic").assertIsSelected()
    }

    @Test fun terminatorOnlyEnabledForRealistic() {
        setUp()
        rule.onNodeWithTag("toggle:Day/night terminator").performScrollTo().performClick()
        assertEquals(false, settings.dayNightTerminator)
        rule.onNodeWithTag("style:Realistic").performScrollTo().performClick()
        rule.onNodeWithTag("toggle:Day/night terminator").performScrollTo().performClick()
        assertEquals(true, settings.dayNightTerminator)
    }

    @Test fun displayToggles() {
        setUp()
        val waves = rule.onNode(isToggleable() and hasAnyAncestor(hasTestTag("toggle:Signal waves")))
        waves.performScrollTo().assertIsOn()
        rule.onNodeWithTag("toggle:Signal waves").performClick()
        waves.assertIsOff()
        assertEquals(false, settings.signalWaves)
        rule.onNodeWithTag("toggle:True-scale orbits").performScrollTo().performClick()
        assertTrue(settings.trueScaleOrbits)
    }

    @Test fun aboutShowsVersionAndLongPressOpensDebug() {
        setUp()
        rule.onNodeWithText("Orbital data updated 2 h ago").performScrollTo()
        rule.onNodeWithTag("version").performScrollTo().assertTextContains("1.0.0", substring = true).performTouchInput { longClick() }
        rule.waitForIdle()
        assertTrue(debugOpened)
        rule.onNodeWithTag("link:GitHub repository").performScrollTo()
        rule.onNodeWithTag("link:Check for updates").performScrollTo()
    }

    @Test fun missingSensorShowsNotAvailable() {
        rule.setContent { ZenitTheme { Metric("Pressure", "995.2", unit = "hPa", available = false) } }
        rule.onNodeWithTag("metric:Pressure").assertTextContains("—").assertTextContains("Not available")
    }
}
