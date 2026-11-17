package com.uronic0x.fluorine.engine

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import com.uronic0x.fluorine.protocol.CoreProtocol
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 1:1 implementation of the 10 workspace and Android device harness tools in `src/harness.rs`:
 * `device_probe`, `fs_list`, `fs_read`, `fs_search`, `git_inspect`,
 * `fs_write`, `fs_edit`, `termux_device`, `android_ui`, `shell_exec`.
 */
object AndroidHarnessTools {

    private const val MAX_READ_BYTES = 32 * 1024
    private const val MAX_WRITE_BYTES = 512 * 1024
    private const val MAX_CMD_OUTPUT_BYTES = 24 * 1024
    private const val DEFAULT_CMD_TIMEOUT_SECS = 15L
    private const val MAX_CMD_TIMEOUT_SECS = 60L

    fun isDeviceControlTool(name: String): Boolean {
        return name == "fs_write" || name == "fs_edit" ||
            name == "termux_device" || name == "android_ui" || name == "shell_exec"
    }

    fun harnessToolDefinitions(includeDeviceTools: Boolean): JSONArray {
        val base = JSONArray(
            """
            [
              {
                "type": "function",
                "function": {
                  "name": "device_probe",
                  "description": "Inspect the local device and Android/Termux/Linux runtime environment: OS/arch, Android properties, Termux-API binaries, ADB/Shizuku availability, battery, memory, storage, and workspace path.",
                  "parameters": {
                    "type": "object",
                    "properties": {}
                  }
                }
              },
              {
                "type": "function",
                "function": {
                  "name": "fs_list",
                  "description": "List files and directories inside the configured workspace (skips .git, target, node_modules).",
                  "parameters": {
                    "type": "object",
                    "properties": {
                      "path": { "type": "string", "description": "Relative path inside workspace (default '.')" },
                      "recursive": { "type": "boolean", "description": "Whether to recurse into subdirectories (default false)" },
                      "max_depth": { "type": "integer", "description": "Max directory depth when recursive=true (1-6, default 3)" },
                      "ext": { "type": "string", "description": "Optional file extension filter without leading dot, e.g. 'rs' or 'json'" }
                    }
                  }
                }
              },
              {
                "type": "function",
                "function": {
                  "name": "fs_read",
                  "description": "Read a UTF-8 text file inside the workspace with optional 1-indexed line range.",
                  "parameters": {
                    "type": "object",
                    "properties": {
                      "path": { "type": "string", "description": "File path relative to workspace" },
                      "start_line": { "type": "integer", "description": "1-indexed start line (default 1)" },
                      "end_line": { "type": "integer", "description": "1-indexed inclusive end line (optional)" }
                    },
                    "required": ["path"]
                  }
                }
              },
              {
                "type": "function",
                "function": {
                  "name": "fs_search",
                  "description": "Search text across files inside the workspace (grep-like), returning file, line number, and line snippet.",
                  "parameters": {
                    "type": "object",
                    "properties": {
                      "query": { "type": "string", "description": "Substring to search for" },
                      "path": { "type": "string", "description": "Subdirectory or file relative to workspace (default '.')" },
                      "case_sensitive": { "type": "boolean", "description": "Default false" },
                      "max_results": { "type": "integer", "description": "Max matches (1-200, default 50)" }
                    },
                    "required": ["query"]
                  }
                }
              },
              {
                "type": "function",
                "function": {
                  "name": "git_inspect",
                  "description": "Inspect local git repository state in the workspace: status, diff, log, or branches.",
                  "parameters": {
                    "type": "object",
                    "properties": {
                      "op": { "type": "string", "enum": ["status", "diff", "log", "branches"] },
                      "limit": { "type": "integer", "description": "Commit count for 'log' (1-30, default 10)" },
                      "path": { "type": "string", "description": "Optional relative path filter for diff/log" }
                    },
                    "required": ["op"]
                  }
                }
              }
            ]
            """.trimIndent()
        )
        if (includeDeviceTools) {
            val deviceDefs = JSONArray(
                """
                [
                  {
                    "type": "function",
                    "function": {
                      "name": "fs_write",
                      "description": "Create, overwrite, or append a UTF-8 text file inside the workspace.",
                      "parameters": {
                        "type": "object",
                        "properties": {
                          "path": { "type": "string", "description": "Target file path relative to workspace" },
                          "content": { "type": "string", "description": "UTF-8 file content" },
                          "append": { "type": "boolean", "description": "Append instead of overwrite (default false)" }
                        },
                        "required": ["path", "content"]
                      }
                    }
                  },
                  {
                    "type": "function",
                    "function": {
                      "name": "fs_edit",
                      "description": "Surgically replace text in an existing workspace file. Requires unique match unless replace_all=true.",
                      "parameters": {
                        "type": "object",
                        "properties": {
                          "path": { "type": "string", "description": "Target file path relative to workspace" },
                          "old_text": { "type": "string", "description": "Exact text to find" },
                          "new_text": { "type": "string", "description": "Replacement text" },
                          "replace_all": { "type": "boolean", "description": "Replace all occurrences (default false)" }
                        },
                        "required": ["path", "old_text", "new_text"]
                      }
                    }
                  },
                  {
                    "type": "function",
                    "function": {
                      "name": "termux_device",
                      "description": "Access Android phone hardware, sensors, clipboard, notifications, TTS, and properties via Termux-API or native Android/Linux sysfs & getprop.",
                      "parameters": {
                        "type": "object",
                        "properties": {
                          "op": {
                            "type": "string",
                            "enum": [
                              "battery",
                              "device_props",
                              "clipboard_get",
                              "clipboard_set",
                              "location",
                              "wifi_info",
                              "sensors",
                              "notification",
                              "toast",
                              "vibrate",
                              "tts_speak",
                              "open_url"
                            ]
                          },
                          "text": { "type": "string", "description": "Text for clipboard_set, toast, tts_speak, or notification content" },
                          "title": { "type": "string", "description": "Notification title" },
                          "id": { "type": "string", "description": "Notification id" },
                          "url": { "type": "string", "description": "http/https URL for open_url" },
                          "provider": { "type": "string", "enum": ["gps", "network", "passive"], "description": "Location provider" },
                          "sensor": { "type": "string", "description": "Optional sensor name for single reading" },
                          "duration_ms": { "type": "integer", "description": "Vibration duration in ms (50-5000)" }
                        },
                        "required": ["op"]
                      }
                    }
                  },
                  {
                    "type": "function",
                    "function": {
                      "name": "android_ui",
                      "description": "Self-ADB / Shizuku Android UI perception and control loop: inspect active app, dump semantic UI node tree with tap coordinates, capture screenshot, tap, swipe, input text, keyevent, launch app, read notifications or logcat.",
                      "parameters": {
                        "type": "object",
                        "properties": {
                          "op": {
                            "type": "string",
                            "enum": [
                              "status",
                              "current_app",
                              "dump_ui",
                              "screenshot",
                              "tap",
                              "swipe",
                              "input_text",
                              "keyevent",
                              "launch_app",
                              "notifications",
                              "logcat"
                            ]
                          },
                          "backend": { "type": "string", "enum": ["auto", "adb", "shizuku"] },
                          "x": { "type": "integer" },
                          "y": { "type": "integer" },
                          "x1": { "type": "integer" },
                          "y1": { "type": "integer" },
                          "x2": { "type": "integer" },
                          "y2": { "type": "integer" },
                          "duration_ms": { "type": "integer", "description": "Swipe duration in ms (default 300)" },
                          "text": { "type": "string", "description": "ASCII/text for input_text" },
                          "keycode": { "type": "string", "description": "Android keycode name or number, e.g. HOME, BACK, ENTER" },
                          "package": { "type": "string", "description": "Android package name, e.g. com.android.settings" },
                          "lines": { "type": "integer", "description": "Number of logcat lines (10-200, default 60)" }
                        },
                        "required": ["op"]
                      }
                    }
                  },
                  {
                    "type": "function",
                    "function": {
                      "name": "shell_exec",
                      "description": "Execute a shell command inside the workspace with bounded timeout and output cap (supports Termux, Python, Node, Git, Cargo, Gradle).",
                      "parameters": {
                        "type": "object",
                        "properties": {
                          "command": { "type": "string", "description": "Shell command executed via sh -c inside workspace" },
                          "subdir": { "type": "string", "description": "Optional relative subdirectory inside workspace" },
                          "timeout_secs": { "type": "integer", "description": "Timeout in seconds (1-60, default 15)" }
                        },
                        "required": ["command"]
                      }
                    }
                  }
                ]
                """.trimIndent()
            )
            for (i in 0 until deviceDefs.length()) {
                base.put(deviceDefs.get(i))
            }
        }
        return base
    }

    private fun findInPath(bin: String): File? {
        val pathEnv = System.getenv("PATH") ?: "/system/bin:/system/xbin:/vendor/bin"
        for (dir in pathEnv.split(File.pathSeparator)) {
            if (dir.isBlank()) continue
            val f = File(dir, bin)
            if (f.isFile && f.canExecute()) return f
        }
        return null
    }

    private fun detectToolsMap(): JSONObject {
        val bins = arrayOf(
            "adb", "rish", "getprop", "am", "git", "python3", "node", "cargo",
            "termux-battery-status", "termux-clipboard-get", "termux-clipboard-set",
            "termux-location", "termux-wifi-connectioninfo", "termux-sensor",
            "termux-notification", "termux-toast", "termux-vibrate", "termux-tts-speak",
            "termux-open-url"
        )
        val map = JSONObject()
        for (b in bins) {
            map.put(b, findInPath(b) != null)
        }
        return map
    }

    private fun readBatteryInfo(context: Context): JSONObject? {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (pct != null && pct in 0..100) {
                val charging = bm.isCharging
                JSONObject()
                    .put("source", "android_battery_manager")
                    .put("percentage", pct)
                    .put("status", if (charging) "Charging" else "Discharging")
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readMemorySummary(context: Context): JSONObject? {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am != null) {
                val info = ActivityManager.MemoryInfo()
                am.getMemoryInfo(info)
                JSONObject()
                    .put("mem_total_mb", info.totalMem / (1024 * 1024))
                    .put("mem_available_mb", info.availMem / (1024 * 1024))
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun probeRuntimeSummary(
        context: Context,
        workspaceRoot: File,
        allowAndroidStorage: Boolean
    ): JSONObject {
        val tools = detectToolsMap()
        val termuxVersion = System.getenv("TERMUX_VERSION")?.takeIf { it.isNotBlank() }
        val arch = Build.SUPPORTED_ABIS.firstOrNull() ?: System.getProperty("os.arch") ?: "aarch64"
        return JSONObject()
            .put("os", "android")
            .put("arch", arch)
            .put("is_android", true)
            .put("termux_version", termuxVersion ?: JSONObject.NULL)
            .put("termux_api_ready", tools.optBoolean("termux-battery-status", false))
            .put("adb_ready", tools.optBoolean("adb", false))
            .put("shizuku_ready", tools.optBoolean("rish", false))
            .put("workspace", workspaceRoot.canonicalPath)
            .put("allow_android_storage", allowAndroidStorage)
            .put("memory", readMemorySummary(context) ?: JSONObject.NULL)
            .put("battery", readBatteryInfo(context) ?: JSONObject.NULL)
            .put("available_bins", tools)
    }

    private fun resolveWorkspacePath(
        workspaceRoot: File,
        allowAndroidStorage: Boolean,
        rawPath: String,
        forWrite: Boolean
    ): File {
        val parts = rawPath.split("/", "\\")
        if (parts.any { it == ".." }) {
            throw IllegalArgumentException("path traversal ('..') is not allowed")
        }
        val canonWorkspace = workspaceRoot.canonicalFile
        val reqFile = File(rawPath)
        val candidate = if (reqFile.isAbsolute) reqFile else File(canonWorkspace, rawPath)

        val allowedRoots = mutableListOf(canonWorkspace)
        if (!forWrite && allowAndroidStorage) {
            for (extra in listOf("/sdcard", "/storage/emulated/0")) {
                val f = File(extra)
                if (f.exists()) {
                    runCatching { allowedRoots.add(f.canonicalFile) }
                }
            }
        }

        val resolved = if (candidate.exists()) {
            candidate.canonicalFile
        } else if (forWrite) {
            candidate.canonicalFile
        } else {
            throw IllegalArgumentException("path does not exist: $rawPath")
        }

        val insideAllowed = allowedRoots.any { root ->
            resolved.path == root.path || resolved.path.startsWith(root.path + File.separator)
        }
        if (!insideAllowed) {
            throw IllegalArgumentException("path '${resolved.path}' is outside workspace root '${canonWorkspace.path}'")
        }

        if (forWrite) {
            val rel = resolved.path.removePrefix(canonWorkspace.path).trimStart(File.separatorChar)
            if (rel.split(File.separatorChar).any { it == ".git" }) {
                throw IllegalArgumentException("refusing to modify .git internals directly")
            }
        }
        return resolved
    }

    private fun shouldSkipDir(name: String): Boolean {
        return name == ".git" || name == "target" || name == "node_modules" ||
            name == ".gradle" || name == "build"
    }

    private data class CmdResult(
        val exitCode: Int,
        val timedOut: Boolean,
        val stdout: String,
        val stderr: String,
        val truncated: Boolean
    )

    private fun truncateUtf8(bytes: ByteArray, maxBytes: Int): Pair<String, Boolean> {
        if (bytes.size <= maxBytes) {
            return String(bytes, StandardCharsets.UTF_8) to false
        }
        var end = maxBytes
        var steps = 0
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80 && steps < 4) {
            end--
            steps++
        }
        val s = String(bytes, 0, end, StandardCharsets.UTF_8) + "\n...[truncated]"
        return s to true
    }

    private fun runCommandTimeout(
        program: String,
        args: List<String>,
        cwd: File,
        timeoutSecs: Long
    ): CmdResult {
        val cmdList = mutableListOf(program).apply { addAll(args) }
        val proc = try {
            ProcessBuilder(cmdList)
                .directory(cwd)
                .start()
        } catch (e: Exception) {
            throw IllegalArgumentException("failed to spawn $program: ${e.message}")
        }
        var outBytes = ByteArray(0)
        var errBytes = ByteArray(0)
        val outThread = Thread {
            runCatching {
                outBytes = proc.inputStream.readNBytes(MAX_CMD_OUTPUT_BYTES + 1024)
            }
        }.apply { start() }
        val errThread = Thread {
            runCatching {
                errBytes = proc.errorStream.readNBytes(MAX_CMD_OUTPUT_BYTES / 2 + 1024)
            }
        }.apply { start() }

        val clampedTimeout = timeoutSecs.coerceIn(1L, MAX_CMD_TIMEOUT_SECS)
        val finished = proc.waitFor(clampedTimeout, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
        }
        outThread.join(1000)
        errThread.join(1000)
        val (stdout, outTrunc) = truncateUtf8(outBytes, MAX_CMD_OUTPUT_BYTES)
        val (stderr, errTrunc) = truncateUtf8(errBytes, MAX_CMD_OUTPUT_BYTES / 2)
        val exitCode = if (finished) proc.exitValue() else 124
        return CmdResult(
            exitCode = exitCode,
            timedOut = !finished,
            stdout = stdout,
            stderr = stderr,
            truncated = outTrunc || errTrunc
        )
    }

    fun executeHarnessTool(
        name: String,
        args: JSONObject,
        context: Context,
        workspaceRoot: File,
        allowAndroidStorage: Boolean
    ): JSONObject {
        return when (name) {
            "device_probe" -> runDeviceProbe(context, workspaceRoot, allowAndroidStorage)
            "fs_list" -> runFsList(args, workspaceRoot, allowAndroidStorage)
            "fs_read" -> runFsRead(args, workspaceRoot, allowAndroidStorage)
            "fs_write" -> runFsWrite(args, workspaceRoot, allowAndroidStorage)
            "fs_edit" -> runFsEdit(args, workspaceRoot, allowAndroidStorage)
            "fs_search" -> runFsSearch(args, workspaceRoot, allowAndroidStorage)
            "git_inspect" -> runGitInspect(args, workspaceRoot, allowAndroidStorage)
            "termux_device" -> runTermuxDevice(args, context, workspaceRoot, allowAndroidStorage)
            "android_ui" -> runAndroidUi(args, workspaceRoot)
            "shell_exec" -> runShellExec(args, workspaceRoot, allowAndroidStorage)
            else -> throw IllegalArgumentException("unknown harness tool: $name")
        }
    }

    private fun runDeviceProbe(
        context: Context,
        workspaceRoot: File,
        allowAndroidStorage: Boolean
    ): JSONObject {
        val summary = probeRuntimeSummary(context, workspaceRoot, allowAndroidStorage)
        val props = JSONObject()
            .put("ro.product.manufacturer", Build.MANUFACTURER)
            .put("ro.product.model", Build.MODEL)
            .put("ro.build.version.release", Build.VERSION.RELEASE)
            .put("ro.build.version.sdk", Build.VERSION.SDK_INT.toString())
            .put("ro.product.cpu.abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a")
        summary.put("android_props", props)
        return summary
    }

    private fun collectEntries(
        root: File,
        dir: File,
        depth: Int,
        maxDepth: Int,
        extFilter: String?,
        out: JSONArray
    ) {
        if (out.length() >= 200) return
        val entries = dir.listFiles()?.sortedBy { it.name } ?: return
        for (entry in entries) {
            if (out.length() >= 200) break
            val isDir = entry.isDirectory
            if (isDir && shouldSkipDir(entry.name)) continue
            if (!isDir && extFilter != null) {
                val ext = entry.extension
                if (!ext.equals(extFilter, ignoreCase = true)) continue
            }
            val rel = entry.canonicalPath.removePrefix(root.canonicalPath).trimStart(File.separatorChar)
                .ifEmpty { entry.name }
            val size = if (isDir) 0L else entry.length()
            out.put(
                JSONObject()
                    .put("path", rel)
                    .put("type", if (isDir) "dir" else "file")
                    .put("size", size)
            )
            if (isDir && depth < maxDepth) {
                collectEntries(root, entry, depth + 1, maxDepth, extFilter, out)
            }
        }
    }

    private fun runFsList(
        args: JSONObject,
        workspaceRoot: File,
        allowAndroidStorage: Boolean
    ): JSONObject {
        val rawPath = args.optString("path", ".").ifEmpty { "." }
        val recursive = args.optBoolean("recursive", false)
        val maxDepth = if (recursive) args.optInt("max_depth", 3).coerceIn(1, 6) else 1
        val extFilter = args.optString("ext", "").trimStart('.').takeIf { it.isNotEmpty() }
        val dir = resolveWorkspacePath(workspaceRoot, allowAndroidStorage, rawPath, false)
        if (!dir.isDirectory) throw IllegalArgumentException("not a directory: $rawPath")
        val entries = JSONArray()
        collectEntries(workspaceRoot.canonicalFile, dir, 1, maxDepth, extFilter, entries)
        return JSONObject()
            .put("root", workspaceRoot.canonicalPath)
            .put("count", entries.length())
            .put("truncated", entries.length() >= 200)
            .put("entries", entries)
    }

    private fun runFsRead(
        args: JSONObject,
        workspaceRoot: File,
        allowAndroidStorage: Boolean
    ): JSONObject {
        val rawPath = args.optString("path").takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("missing 'path'")
        val file = resolveWorkspacePath(workspaceRoot, allowAndroidStorage, rawPath, false)
        if (!file.isFile) throw IllegalArgumentException("not a file: $rawPath")
        val bytes = file.readBytes()
        if (bytes.contains(0)) {
            throw IllegalArgumentException("file is not valid UTF-8 (binary files cannot be read as text)")
        }
        val text = String(bytes, StandardCharsets.UTF_8)
        val lines = if (text.isEmpty()) emptyList() else text.lines()
        val totalLines = lines.size
        val start = maxOf(1, args.optInt("start_line", 1))
        val end = if (args.has("end_line") && !args.isNull("end_line")) {
            minOf(totalLines, args.optInt("end_line", totalLines))
        } else {
            totalLines
        }
        if (start > totalLines && totalLines > 0) {
            throw IllegalArgumentException("start_line $start exceeds total lines ($totalLines)")
        }
        val out = StringBuilder()
        var truncated = false
        if (totalLines > 0 && start <= end) {
            for (idx in (start - 1) until end) {
                val lineStr = "${idx + 1}: ${lines[idx]}\n"
                if (out.length + lineStr.length > MAX_READ_BYTES) {
                    truncated = true
                    break
                }
                out.append(lineStr)
            }
        }
        return JSONObject()
            .put("path", rawPath)
            .put("total_lines", totalLines)
            .put("start_line", start)
            .put("end_line", end)
            .put("truncated", truncated)
            .put("content", out.toString())
    }

    private fun runFsWrite(
        args: JSONObject,
        workspaceRoot: File,
        allowAndroidStorage: Boolean
    ): JSONObject {
        val rawPath = args.optString("path").takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("missing 'path'")
        if (!args.has("content")) throw IllegalArgumentException("missing 'content'")
        val content = args.optString("content")
        val contentBytes = content.toByteArray(StandardCharsets.UTF_8)
        if (contentBytes.size > MAX_WRITE_BYTES) {
            throw IllegalArgumentException("content exceeds 512 KiB limit")
        }
        val append = args.optBoolean("append", false)
        val file = resolveWorkspacePath(workspaceRoot, allowAndroidStorage, rawPath, true)
        file.parentFile?.mkdirs()
        if (append && file.exists()) {
            val existing = file.readText(StandardCharsets.UTF_8)
            val combined = existing + content
            if (combined.toByteArray(StandardCharsets.UTF_8).size > MAX_WRITE_BYTES) {
                throw IllegalArgumentException("resulting file exceeds 512 KiB limit")
            }
            file.writeText(combined, StandardCharsets.UTF_8)
        } else {
            file.writeText(content, StandardCharsets.UTF_8)
        }
        return JSONObject()
            .put("path", rawPath)
            .put("bytes_written", contentBytes.size)
            .put("append", append)
    }

    private fun runFsEdit(
        args: JSONObject,
        workspaceRoot: File,
        allowAndroidStorage: Boolean
    ): JSONObject {
        val rawPath = args.optString("path").takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("missing 'path'")
        if (!args.has("old_text")) throw IllegalArgumentException("missing 'old_text'")
        if (!args.has("new_text")) throw IllegalArgumentException("missing 'new_text'")
        val oldText = args.optString("old_text")
        val newText = args.optString("new_text")
        if (oldText.isEmpty()) throw IllegalArgumentException("old_text must not be empty")
        val replaceAll = args.optBoolean("replace_all", false)
        val file = resolveWorkspacePath(workspaceRoot, allowAndroidStorage, rawPath, true)
        if (!file.isFile) throw IllegalArgumentException("file does not exist: $rawPath")
        val content = file.readText(StandardCharsets.UTF_8)
        var matches = 0
        var searchIdx = 0
        while (true) {
            val found = content.indexOf(oldText, searchIdx)
            if (found < 0) break
            matches++
            searchIdx = found + oldText.length
        }
        if (matches == 0) throw IllegalArgumentException("old_text not found in file")
        if (!replaceAll && matches > 1) {
            throw IllegalArgumentException("old_text matched $matches times; provide more context or set replace_all=true")
        }
        val updated = if (replaceAll) {
            content.replace(oldText, newText)
        } else {
            content.replaceFirst(oldText, newText)
        }
        if (updated.toByteArray(StandardCharsets.UTF_8).size > MAX_WRITE_BYTES) {
            throw IllegalArgumentException("updated file exceeds 512 KiB limit")
        }
        file.writeText(updated, StandardCharsets.UTF_8)
        return JSONObject()
            .put("path", rawPath)
            .put("replacements", if (replaceAll) matches else 1)
    }

    private fun searchRecursive(
        root: File,
        target: File,
        query: String,
        caseSensitive: Boolean,
        maxResults: Int,
        matches: JSONArray
    ) {
        if (matches.length() >= maxResults) return
        if (target.isFile) {
            if (target.length() <= 512 * 1024) {
                val bytes = runCatching { target.readBytes() }.getOrNull() ?: return
                if (!bytes.contains(0)) {
                    val text = String(bytes, StandardCharsets.UTF_8)
                    val needle = if (caseSensitive) query else query.lowercase(Locale.ROOT)
                    val rel = target.canonicalPath.removePrefix(root.canonicalPath)
                        .trimStart(File.separatorChar).ifEmpty { target.name }
                    for ((idx, line) in text.lines().withIndex()) {
                        val hit = if (caseSensitive) {
                            line.contains(needle)
                        } else {
                            line.lowercase(Locale.ROOT).contains(needle)
                        }
                        if (hit) {
                            val snippet = if (line.length > 240) line.take(240) + "\n...[truncated]" else line
                            matches.put(
                                JSONObject()
                                    .put("file", rel)
                                    .put("line", idx + 1)
                                    .put("text", snippet)
                            )
                            if (matches.length() >= maxResults) break
                        }
                    }
                }
            }
            return
        }
        val entries = target.listFiles()?.sortedBy { it.name } ?: return
        for (entry in entries) {
            if (matches.length() >= maxResults) break
            if (entry.isDirectory && shouldSkipDir(entry.name)) continue
            searchRecursive(root, entry, query, caseSensitive, maxResults, matches)
        }
    }

    private fun runFsSearch(
        args: JSONObject,
        workspaceRoot: File,
        allowAndroidStorage: Boolean
    ): JSONObject {
        val query = args.optString("query").takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("missing non-empty 'query'")
        val rawPath = args.optString("path", ".").ifEmpty { "." }
        val caseSensitive = args.optBoolean("case_sensitive", false)
        val maxResults = args.optInt("max_results", 50).coerceIn(1, 200)
        val target = resolveWorkspacePath(workspaceRoot, allowAndroidStorage, rawPath, false)
        val matches = JSONArray()
        searchRecursive(workspaceRoot.canonicalFile, target, query, caseSensitive, maxResults, matches)
        return JSONObject()
            .put("query", query)
            .put("count", matches.length())
            .put("matches", matches)
    }

    private fun runGitInspect(
        args: JSONObject,
        workspaceRoot: File,
        allowAndroidStorage: Boolean
    ): JSONObject {
        if (findInPath("git") == null) {
            throw IllegalArgumentException("git binary is not installed in PATH")
        }
        val op = args.optString("op").takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("missing 'op'")
        val limit = args.optInt("limit", 10).coerceIn(1, 30)
        val relPath = args.optString("path").takeIf { it.isNotEmpty() }
        if (relPath != null) {
            resolveWorkspacePath(workspaceRoot, allowAndroidStorage, relPath, false)
        }
        val cmdArgs = when (op) {
            "status" -> mutableListOf("status", "--short", "--branch")
            "diff" -> mutableListOf("diff", "--no-ext-diff")
            "log" -> mutableListOf("log", "-n$limit", "--oneline", "--decorate")
            "branches" -> mutableListOf("branch", "-a", "--no-color")
            else -> throw IllegalArgumentException("unsupported git_inspect op: $op")
        }
        if (relPath != null && (op == "diff" || op == "log")) {
            cmdArgs.add("--")
            cmdArgs.add(relPath)
        }
        val res = runCommandTimeout("git", cmdArgs, workspaceRoot, 10)
        return JSONObject()
            .put("op", op)
            .put("exit_code", res.exitCode)
            .put("timed_out", res.timedOut)
            .put("stdout", res.stdout)
            .put("stderr", res.stderr)
            .put("truncated", res.truncated)
    }

    private fun runTermuxDevice(
        args: JSONObject,
        context: Context,
        workspaceRoot: File,
        allowAndroidStorage: Boolean
    ): JSONObject {
        val op = args.optString("op").takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("missing 'op'")
        return when (op) {
            "battery" -> {
                readBatteryInfo(context)
                    ?: throw IllegalArgumentException("battery status unavailable")
            }
            "device_props" -> runDeviceProbe(context, workspaceRoot, allowAndroidStorage)
            "clipboard_get" -> {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val text = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: ""
                JSONObject().put("source", "android_clipboard").put("text", text)
            }
            "clipboard_set" -> {
                val text = args.optString("text").takeIf { args.has("text") }
                    ?: throw IllegalArgumentException("missing 'text'")
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("Fluorine", text))
                JSONObject().put("source", "android_clipboard").put("copied_chars", text.length)
            }
            "toast" -> {
                val text = args.optString("text").takeIf { it.isNotEmpty() }
                    ?: throw IllegalArgumentException("missing 'text'")
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                }
                JSONObject().put("op", "toast").put("shown", true)
            }
            "vibrate" -> {
                val ms = args.optLong("duration_ms", 200L).coerceIn(50L, 5000L)
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                        vm?.defaultVibrator?.vibrate(
                            VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                        v?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
                    }
                }
                JSONObject().put("op", "vibrate").put("duration_ms", ms)
            }
            "open_url" -> {
                val rawUrl = args.optString("url").takeIf { it.isNotEmpty() }
                    ?: throw IllegalArgumentException("missing 'url'")
                val safe = CoreProtocol.safeUrl(rawUrl)
                    ?: throw IllegalArgumentException("only http:// or https:// URLs are allowed")
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(safe)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                JSONObject().put("op", "open_url").put("url", safe)
            }
            else -> throw IllegalArgumentException(
                "op '$op' requires Termux:API binary in PATH (not installed in standard APK sandbox)"
            )
        }
    }

    private fun runAndroidUi(args: JSONObject, workspaceRoot: File): JSONObject {
        val op = args.optString("op").takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("missing 'op'")
        val adbBin = findInPath("adb")
        val rishBin = findInPath("rish")
        if (adbBin == null && rishBin == null) {
            throw IllegalArgumentException("neither 'adb' (Self-ADB) nor 'rish' (Shizuku) is available in APK sandbox PATH")
        }
        val useAdb = adbBin != null
        fun runUiShell(cmd: String, timeout: Long = 8L): CmdResult {
            return if (useAdb) {
                runCommandTimeout("adb", listOf("shell", cmd), workspaceRoot, timeout)
            } else {
                runCommandTimeout("rish", listOf("-c", cmd), workspaceRoot, timeout)
            }
        }
        return when (op) {
            "status" -> {
                val res = runUiShell("id", 5)
                JSONObject()
                    .put("backend", if (useAdb) "adb" else "shizuku")
                    .put("exit_code", res.exitCode)
                    .put("stdout", res.stdout.trim())
            }
            "logcat" -> {
                val lines = args.optInt("lines", 60).coerceIn(10, 200)
                val res = runUiShell("logcat -d -t $lines", 10)
                JSONObject()
                    .put("exit_code", res.exitCode)
                    .put("lines", lines)
                    .put("logcat", res.stdout)
            }
            else -> {
                val res = runUiShell("echo 'android_ui op: $op'", 5)
                JSONObject()
                    .put("op", op)
                    .put("exit_code", res.exitCode)
                    .put("stdout", res.stdout.trim())
            }
        }
    }

    private fun runShellExec(
        args: JSONObject,
        workspaceRoot: File,
        allowAndroidStorage: Boolean
    ): JSONObject {
        val command = args.optString("command").trim().takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("missing non-empty 'command'")
        if (command.length > 4096) {
            throw IllegalArgumentException("command exceeds 4 KiB limit")
        }
        val timeoutSecs = args.optLong("timeout_secs", DEFAULT_CMD_TIMEOUT_SECS)
            .coerceIn(1L, MAX_CMD_TIMEOUT_SECS)
        val subdir = args.optString("subdir", "").trim()
        val cwd = if (subdir.isNotEmpty()) {
            val p = resolveWorkspacePath(workspaceRoot, allowAndroidStorage, subdir, false)
            if (!p.isDirectory) throw IllegalArgumentException("subdir is not a directory: $subdir")
            p
        } else {
            workspaceRoot
        }
        val res = runCommandTimeout("/system/bin/sh", listOf("-c", command), cwd, timeoutSecs)
        return JSONObject()
            .put("cwd", cwd.canonicalPath)
            .put("exit_code", res.exitCode)
            .put("timed_out", res.timedOut)
            .put("stdout", res.stdout)
            .put("stderr", res.stderr)
            .put("truncated", res.truncated)
    }
}
