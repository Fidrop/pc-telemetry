package com.pctelemetry.dashboard

import android.os.ParcelFileDescriptor
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Use a normal Android frame clock: ComposeTestRule substitutes a controlled clock. */
@RunWith(AndroidJUnit4::class)
class DashboardPerformanceTest {
    @Test fun recordDemoFrameTimings() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Explicit emulator performance capture required",
            InstrumentationRegistry.getArguments().getString("capturePerformance") == "true")
        val context = instrumentation.targetContext
        val directory = File(context.getExternalFilesDir(null), "performance").apply { mkdirs() }
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            Thread.sleep(3000)
            scenario.onActivity { ViewModelProvider(it)[DashboardViewModel::class.java].startDemo() }
            for (theme in DashboardTheme.entries) {
                scenario.onActivity { activity ->
                    ViewModelProvider(activity)[DashboardViewModel::class.java].changeSettings { it.copy(theme = theme) }
                }
                Thread.sleep(3000)
                shell("dumpsys gfxinfo ${context.packageName} reset")
                Thread.sleep(15_000)
                val report = shell("dumpsys gfxinfo ${context.packageName} framestats")
                File(directory, "${theme.name.lowercase()}-gfxinfo.txt").writeText(report)
                val frames = Regex("Total frames rendered: (\\d+)").find(report)?.groupValues?.get(1)?.toInt() ?: 0
                assertTrue("No measured frames for $theme; capture cannot establish performance", frames > 0)
            }
        }
    }
}
