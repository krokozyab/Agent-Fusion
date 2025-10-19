package com.orchestrator.web

import com.orchestrator.utils.Logger
import java.awt.Desktop
import java.awt.GraphicsEnvironment
import java.net.URI
import kotlin.math.max
import kotlin.concurrent.thread

class BrowserLauncher(
    private val config: WebServerConfig,
    private val backgroundExecutor: BackgroundExecutor = ThreadBackgroundExecutor,
    private val desktopProvider: DesktopProvider = DefaultDesktopProvider,
    private val commandRunner: CommandRunner = RuntimeCommandRunner,
    private val sleeper: Sleeper = ThreadSleeper,
    private val osNameProvider: () -> String = { System.getProperty("os.name") },
    private val log: Logger = Logger.logger("com.orchestrator.web.BrowserLauncher")
) {

    fun launchIfEnabled(url: String = defaultUrl()) {
        if (!config.autoLaunchBrowser) {
            log.debug("Browser auto-launch disabled by configuration")
            return
        }

        backgroundExecutor.execute {
            sleeper.sleep(LAUNCH_DELAY_MS)
            if (tryDesktop(url)) {
                return@execute
            }
            fallbackToCommand(url)
        }
    }

    private fun defaultUrl(): String {
        val scheme = if (config.ssl.enabled) "https" else "http"
        val host = when (config.host) {
            "0.0.0.0", "::", "::1", "*" -> "localhost"
            else -> config.host
        }
        return "$scheme://$host:${config.port}"
    }

    private fun tryDesktop(url: String): Boolean {
        val desktop = desktopProvider.get() ?: return false
        return runCatching {
            desktop.browse(URI(url))
            log.info("Opened browser at {}", url)
            true
        }.onFailure { throwable ->
            log.warn("Desktop browse failed: ${throwable.message}")
        }.getOrDefault(false)
    }

    private fun fallbackToCommand(url: String) {
        val osName = osNameProvider().lowercase()
        val command = when {
            osName.contains("win") -> listOf("cmd", "/c", "start", "\"\"", url)
            osName.contains("mac") -> listOf("open", url)
            else -> listOf("xdg-open", url)
        }

        runCatching {
            commandRunner.run(command)
            log.info("Attempted to launch browser via command {}", command.joinToString(" "))
        }.onFailure { throwable ->
            log.warn("Failed to launch browser for {}: {}", url, throwable.message)
        }
    }

    fun interface BackgroundExecutor {
        fun execute(block: () -> Unit)
    }

    fun interface CommandRunner {
        fun run(command: List<String>)
    }

    fun interface DesktopProvider {
        fun get(): DesktopAdapter?
    }

    fun interface Sleeper {
        fun sleep(millis: Long)
    }

    private object ThreadBackgroundExecutor : BackgroundExecutor {
        override fun execute(block: () -> Unit) {
            thread(name = "browser-launcher", isDaemon = true, block = block)
        }
    }

    private object DefaultDesktopProvider : DesktopProvider {
        override fun get(): DesktopAdapter? {
            return runCatching {
                if (!Desktop.isDesktopSupported() || GraphicsEnvironment.isHeadless()) {
                    return null
                }
                Desktop.getDesktop().takeIf { desktop ->
                    desktop.isSupported(Desktop.Action.BROWSE)
                }?.let(::DesktopWrapper)
            }.getOrNull()
        }
    }

    private object RuntimeCommandRunner : CommandRunner {
        override fun run(command: List<String>) {
            if (command.isEmpty()) return
            ProcessBuilder(command).start()
        }
    }

    fun interface DesktopAdapter {
        fun browse(uri: URI)
    }

    private class DesktopWrapper(private val delegate: Desktop) : DesktopAdapter {
        override fun browse(uri: URI) {
            delegate.browse(uri)
        }
    }

    private object ThreadSleeper : Sleeper {
        override fun sleep(millis: Long) {
            try {
                Thread.sleep(max(0, millis))
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private companion object {
        private const val LAUNCH_DELAY_MS = 500L
    }
}
