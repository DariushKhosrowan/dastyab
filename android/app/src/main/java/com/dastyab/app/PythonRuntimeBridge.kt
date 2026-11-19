package com.dastyab.app

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * PythonRuntimeBridge — embeds real CPython 3.11 via Chaquopy and bridges DastYab's
 * Android storage, harvested VLESS pool, active VPN/proxy ports, InAppBrowserActivity,
 * and native `libdastyab.so` CLI directly to `dastyab_terminal_shell.py`.
 */
object PythonRuntimeBridge {

    interface HostCallbacks {
        fun onStdout(text: String)
        fun onEvent(kind: String, payloadJson: String)
        fun runNativeCli(args: List<String>, outputCallback: (String) -> Unit): Int
    }

    /** Exported to Python (`execute_for_host`) and preserved by ProGuard keep rules. */
    class PythonHostProxy(private val callbacks: HostCallbacks) {
        fun onStdout(text: String?) {
            if (!text.isNullOrEmpty()) callbacks.onStdout(text)
        }

        fun onEvent(kind: String?, payloadJson: String?) {
            if (!kind.isNullOrBlank()) callbacks.onEvent(kind, payloadJson ?: "{}")
        }

        fun runNativeCli(argsJson: String?): Int {
            val args = runCatching {
                TerminalCommandRouter.parseCliArgsJson(argsJson ?: "[]")
            }.getOrDefault(emptyList())
            return callbacks.runNativeCli(args) { chunk -> callbacks.onStdout(chunk) }
        }
    }

    private val initLock = Any()
    @Volatile
    private var envInitialized = false
    private val lastError = AtomicReference<String?>(null)

    fun ensureStarted(context: Context): Boolean {
        synchronized(initLock) {
            return try {
                if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(context.applicationContext))
                }
                if (!envInitialized) {
                    val py = Python.getInstance()
                    val shell = py.getModule("dastyab_terminal_shell")
                    shell.callAttr(
                        "init_env",
                        context.filesDir.absolutePath,
                        context.cacheDir.absolutePath,
                        context.applicationInfo.nativeLibraryDir
                    )
                    envInitialized = true
                }
                lastError.set(null)
                true
            } catch (t: Throwable) {
                lastError.set(t.message ?: t.javaClass.simpleName)
                DastyabLogger.e("PythonRuntime", t, "Failed to start embedded CPython 3.11")
                false
            }
        }
    }

    fun getLastInitError(): String? = lastError.get()

    fun getPythonVersion(context: Context): String {
        if (!ensureStarted(context)) return "Unavailable (${lastError.get() ?: "init error"})"
        return runCatching {
            val py = Python.getInstance()
            val platform = py.getModule("platform")
            "CPython " + platform.callAttr("python_version").toString()
        }.getOrElse { "CPython 3.11" }
    }

    fun getPromptPath(context: Context): String {
        if (!ensureStarted(context)) return "~"
        return runCatching {
            Python.getInstance()
                .getModule("dastyab_terminal_shell")
                .callAttr("prompt_path")
                .toString()
        }.getOrDefault("~")
    }

    fun syncProfile(context: Context): Int {
        if (!ensureStarted(context)) return 0
        return runCatching {
            val settings = TunnelConfig.load(context)
            val layerUri = VpnLayerStore.getActiveLayer(context).configUri
            val harvested = TunnelConfig.loadHarvestedConfigs(context)
            val nodesJson = TerminalCommandRouter.buildVlessSyncJson(
                activeUri = settings.vlessUri,
                layerUri = layerUri,
                harvested = harvested
            )
            val routeJson = TerminalCommandRouter.buildRouteSyncJson(
                externalVpnActive = NetworkUtils.hasActiveExternalVpn(context),
                dastyabVpnActive = DastyabVpnService.isConnected(),
                bridgePort = LocalProxyService.activePort(),
                upstreamScheme = settings.upstreamProxyScheme,
                upstreamHost = settings.upstreamProxyHost,
                upstreamPort = settings.upstreamProxyPort
            )
            val shell = Python.getInstance().getModule("dastyab_terminal_shell")
            shell.callAttr("sync_nodes", nodesJson, routeJson).toInt()
        }.getOrDefault(0)
    }

    fun executeCommand(context: Context, commandLine: String, callbacks: HostCallbacks): Int {
        if (!ensureStarted(context)) {
            callbacks.onStdout("ERROR: Embedded CPython failed to initialize: ${lastError.get()}\n")
            return 1
        }
        syncProfile(context)
        return try {
            val py = Python.getInstance()
            val shell = py.getModule("dastyab_terminal_shell")
            val hostProxy = PythonHostProxy(callbacks)
            val res: PyObject = shell.callAttr("execute_for_host", commandLine, hostProxy)
            res.toInt()
        } catch (t: Throwable) {
            callbacks.onStdout("ERROR: ${t.message ?: t.javaClass.simpleName}\n")
            DastyabLogger.e("PythonRuntime", t, "Python execution failed")
            1
        }
    }

    fun requestStop(context: Context) {
        if (!Python.isStarted()) return
        runCatching {
            Python.getInstance()
                .getModule("dastyab_terminal_shell")
                .callAttr("request_stop")
        }
    }

    /** Executes native `libdastyab.so` subcommand synchronously and streams output to Terminal Mode. */
    fun execNativeBinary(
        context: Context,
        binary: File,
        args: List<String>,
        onChunk: (String) -> Unit,
        processHolder: AtomicReference<Process?>
    ): Int {
        val compat = CommandCompat.check(args)
        if (compat is CommandCompat.Verdict.Blocked) {
            onChunk("${CommandCompat.blockedTitle(compat)}\n${CommandCompat.blockedMessage(compat)}\n")
            return 2
        }
        if (!binary.isFile || !binary.canExecute()) {
            onChunk("dastyab: native binary not executable on this device\n")
            return 127
        }
        return try {
            val child = ProcessBuilder(listOf(binary.absolutePath) + args).apply {
                directory(context.filesDir)
                environment()["HOME"] = context.filesDir.absolutePath
                environment()["TMPDIR"] = context.cacheDir.absolutePath
                redirectErrorStream(true)
            }.start()
            processHolder.set(child)
            child.outputStream.close()
            child.inputStream.reader(Charsets.UTF_8).use { reader ->
                val buf = CharArray(2048)
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    onChunk(String(buf, 0, n))
                }
            }
            val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3)
            while (System.nanoTime() < deadline) {
                try {
                    return child.exitValue()
                } catch (_: IllegalThreadStateException) {
                    Thread.sleep(40)
                }
            }
            child.destroy()
            onChunk("dastyab: timed out\n")
            124
        } catch (e: Exception) {
            onChunk("dastyab error: ${e.message ?: e.javaClass.simpleName}\n")
            1
        } finally {
            processHolder.set(null)
        }
    }
}
