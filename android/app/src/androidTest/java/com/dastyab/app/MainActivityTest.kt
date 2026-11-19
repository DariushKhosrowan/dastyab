package com.dastyab.app

import android.app.Activity
import android.content.Intent
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun applicationBootstrapsLoggerAndCrashHandler() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        assertTrue("Application must be DastyabApplication", app is DastyabApplication)
        assertNotNull("Logger dir must be initialized by Application", DastyabLogger.getLogDir())
    }

    @Test
    fun bundledEngineRunsOfflineInAppSandbox() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = File(context.applicationInfo.nativeLibraryDir, "libdastyab.so")
        assertTrue("APK must contain an executable engine", engine.canExecute())
        // Use an isolated HOME so CI never touches the user's profiles.
        val home = File(context.cacheDir, "engine-test").apply { mkdirs() }
        for (args in listOf(listOf("--version"), listOf("--help"), listOf("profiles"))) {
            val builder = ProcessBuilder(listOf(engine.absolutePath) + args)
                .directory(home).redirectErrorStream(true)
            builder.environment()["HOME"] = home.absolutePath
            builder.environment()["TMPDIR"] = context.cacheDir.absolutePath
            val log = File(home, "command.log")
            builder.redirectOutput(log)
            val process = builder.start()
            try {
                process.outputStream.close()
                assertTrue("Engine hung: $args", process.waitFor(20, TimeUnit.SECONDS))
                assertEquals("$args: ${log.readText()}", 0, process.exitValue())
                assertTrue("Engine produced no output", log.length() > 0)
            } finally {
                process.destroy()
            }
        }
    }

    @Test
    fun launchRunCommandAndRecreate() {
        activityRule.scenario.onActivity { activity ->
            assertNotNull(activity.findViewById<TextView>(R.id.status))
            activity.findViewById<EditText>(R.id.argsInput).setText("--version")
            activity.findViewById<Button>(R.id.btnRun).performClick()
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        var text = ""
        while (System.nanoTime() < deadline) {
            activityRule.scenario.onActivity {
                text = it.findViewById<TextView>(R.id.output).text.toString()
            }
            if (text.contains("exit code 0")) break
            Thread.sleep(100)
        }
        assertTrue("Command did not complete successfully: $text", text.contains("exit code 0"))
        activityRule.scenario.recreate()
        activityRule.scenario.onActivity {
            assertTrue(it.findViewById<TextView>(R.id.output).text.contains("exit code 0"))
        }
    }

    @Test
    fun allActivitiesLaunchWithoutCrashing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val targets: List<Class<out Activity>> = listOf(
            AboutActivity::class.java,
            SettingsActivity::class.java,
            LogViewerActivity::class.java,
            TunnelSettingsActivity::class.java,
            BlackoutRescueActivity::class.java,
            VpnLayersActivity::class.java,
            NetworkToolboxActivity::class.java,
            CloudflareWorkerActivity::class.java
        )
        for (cls in targets) {
            ActivityScenario.launch<Activity>(Intent(context, cls)).use { scenario ->
                scenario.onActivity { act ->
                    assertTrue("${cls.simpleName} should remain alive", UiSafety.isActivityAlive(act))
                }
                scenario.recreate()
                scenario.onActivity { act ->
                    assertTrue("${cls.simpleName} should remain alive after recreate", UiSafety.isActivityAlive(act))
                }
            }
        }
    }

    @Test
    fun terminalModeSwitchesAndExposesPackagesButton() {
        activityRule.scenario.onActivity { activity ->
            val tabTerminal = activity.findViewById<Button>(R.id.btnTabTerminal)
            val btnPackages = activity.findViewById<Button>(R.id.btnTerminalPackages)
            assertNotNull("btnTabTerminal must exist", tabTerminal)
            assertNotNull("btnTerminalPackages must exist", btnPackages)
            tabTerminal.performClick()
            assertTrue("MainActivity must stay alive after opening Terminal Mode", UiSafety.isActivityAlive(activity))
        }
    }
}
