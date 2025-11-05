package com.orchestrator.web

import com.orchestrator.utils.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.net.URI

class BrowserLauncherTest {

    private val testLogger = Logger.logger("test.browser")

    @Test
    fun `does nothing when auto launch disabled`() {
        val executor = RecordingExecutor()
        val desktopProvider = RecordingDesktopProvider(null)
        val commandRunner = RecordingCommandRunner()
        val sleeper = RecordingSleeper()

        val launcher = BrowserLauncher(
            config = WebServerConfig(autoLaunchBrowser = false),
            backgroundExecutor = executor,
            desktopProvider = desktopProvider,
            commandRunner = commandRunner,
            sleeper = sleeper,
            osNameProvider = { "Linux" },
            log = testLogger
        )

        launcher.launchIfEnabled("http://localhost:8081")

        assertEquals(0, executor.executions)
        assertEquals(0, desktopProvider.invocations)
        assertTrue(commandRunner.commands.isEmpty())
        assertTrue(sleeper.delays.isEmpty())
    }

    @Test
    fun `uses desktop browse when supported`() {
        val executor = RecordingExecutor()
        val desktop = FakeDesktopAdapter()
        val desktopProvider = RecordingDesktopProvider(desktop)
        val commandRunner = RecordingCommandRunner()
        val sleeper = RecordingSleeper()

        val launcher = BrowserLauncher(
            config = WebServerConfig(autoLaunchBrowser = true),
            backgroundExecutor = executor,
            desktopProvider = desktopProvider,
            commandRunner = commandRunner,
            sleeper = sleeper,
            osNameProvider = { "Mac OS X" },
            log = testLogger
        )

        launcher.launchIfEnabled("http://localhost:8081")

        assertEquals(1, executor.executions)
        assertEquals(listOf(500L), sleeper.delays)
        assertEquals(1, desktopProvider.invocations)
        assertEquals(URI("http://localhost:8081"), desktop.lastUri)
        assertTrue(commandRunner.commands.isEmpty())
    }

    @Test
    fun `falls back to platform command when desktop unsupported`() {
        val executor = RecordingExecutor()
        val commandRunner = RecordingCommandRunner()
        val sleeper = RecordingSleeper()

        val launcher = BrowserLauncher(
            config = WebServerConfig(host = "0.0.0.0", port = 9090, autoLaunchBrowser = true),
            backgroundExecutor = executor,
            desktopProvider = RecordingDesktopProvider(null),
            commandRunner = commandRunner,
            sleeper = sleeper,
            osNameProvider = { "Windows 10" },
            log = testLogger
        )

        launcher.launchIfEnabled()

        assertEquals(1, executor.executions)
        assertEquals(listOf(500L), sleeper.delays)
        assertEquals(1, commandRunner.commands.size)
        val command = commandRunner.commands.single()
        assertEquals(listOf("cmd", "/c", "start", "\"\"", "http://localhost:9090"), command)
    }

    @Test
    fun `selects correct command per operating system`() {
        val linuxRunner = RecordingCommandRunner()
        BrowserLauncher(
            config = WebServerConfig(autoLaunchBrowser = true),
            backgroundExecutor = RecordingExecutor(),
            desktopProvider = RecordingDesktopProvider(null),
            commandRunner = linuxRunner,
            sleeper = RecordingSleeper(),
            osNameProvider = { "Linux" },
            log = testLogger
        ).launchIfEnabled("http://localhost:8081")
        assertEquals(listOf("xdg-open", "http://localhost:8081"), linuxRunner.commands.single())

        val macRunner = RecordingCommandRunner()
        BrowserLauncher(
            config = WebServerConfig(autoLaunchBrowser = true),
            backgroundExecutor = RecordingExecutor(),
            desktopProvider = RecordingDesktopProvider(null),
            commandRunner = macRunner,
            sleeper = RecordingSleeper(),
            osNameProvider = { "Mac OS X" },
            log = testLogger
        ).launchIfEnabled("http://localhost:8081")
        assertEquals(listOf("open", "http://localhost:8081"), macRunner.commands.single())
    }

    private class RecordingExecutor : BrowserLauncher.BackgroundExecutor {
        var executions = 0
            private set

        override fun execute(block: () -> Unit) {
            executions += 1
            block()
        }
    }

    private class RecordingDesktopProvider(private val adapter: BrowserLauncher.DesktopAdapter?) :
        BrowserLauncher.DesktopProvider {
        var invocations = 0
            private set

        override fun get(): BrowserLauncher.DesktopAdapter? {
            invocations += 1
            return adapter
        }
    }

    private class RecordingCommandRunner : BrowserLauncher.CommandRunner {
        val commands = mutableListOf<List<String>>()

        override fun run(command: List<String>) {
            commands += command
        }
    }

    private class RecordingSleeper : BrowserLauncher.Sleeper {
        val delays = mutableListOf<Long>()

        override fun sleep(millis: Long) {
            delays += millis
        }
    }

    private class FakeDesktopAdapter : BrowserLauncher.DesktopAdapter {
        var lastUri: URI? = null
            private set

        override fun browse(uri: URI) {
            lastUri = uri
        }
    }
}
