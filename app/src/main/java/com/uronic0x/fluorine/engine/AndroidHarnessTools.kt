package com.uronic0x.fluorine.engine

import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Android/Termux local workspace & harness tools:
 * - fs_list / fs_read / fs_write / fs_edit / fs_search
 * - shell_exec (user-toggleable on-device, with destructive command blocklist & timeout)
 * - git_cmd (status/diff/log/branch/show/rev-parse)
 * - termux_api (battery-status / clipboard-get / clipboard-set / notification / toast / tts-speak / wifi-connectioninfo)
 * - self_adb (devices / getprop / dumpsys / pm / wm / logcat / input / am)
 */
object AndroidHarnessTools {

    private const val MAX_FILE_READ_BYTES = 128 * 1024
    private const val MAX_FILE_WRITE_BYTES = 512 * 1024
    private const val MAX_CMD_OUTPUT_BYTES = 64 * 1024
    private const val DEFAULT_SHELL_TIMEOUT_SECS = 20L

    val TOOL_NAMES = setOf(
        "fs_list",
        "fs_read",
        "fs_write",
        "fs_edit",
        "fs_search",
        "shell_exec",
        "git_cmd",
        "termux_api",
        "self_adb"
    )

    fun declarations(enabledTools: Set<String>? = null): JSONArray {
        val all = JSONArray()
        all.put(
            fnDecl(
                name = "fs_list",
                description = "List files and directories inside the workspace (or read-only /sdcard when enabled). Returns name, type, and size.",
                properties = JSONObject()
                    .put("path", prop("string", "Relative path inside workspace (default '.') or /sdcard/... when enabled"))
                    .put("recursive", prop("boolean", "List recursively up to depth 3 (default false)")),
                required = emptyList()
            )
        )
        all.put(
            fnDecl(
                name = "fs_read",
                description = "Read a UTF-8 text file from workspace (or read-only /sdcard when enabled), optionally slicing by 1-based line range.",
                properties = JSONObject()
                    .put("path", prop("string", "File path relative to workspace"))
                    .put("start_line", prop("integer", "1-based start line (optional)"))
                    .put("end_line", prop("integer", "1-based inclusive end line (optional)")),
                required = listOf("path")
            )
        )
        all.put(
            fnDecl(
                name = "fs_write",
                description = "Create, overwrite, or append a UTF-8 text file inside the workspace directory. Generated files are presented to the user with an export card.",
                properties = JSONObject()
                    .put("path", prop("string", "Relative file path inside workspace (must not escape workspace)"))
                    .put("content", prop("string", "Full text content to write"))
                    .put("append", prop("boolean", "Append instead of overwrite (default false)")),
                required = listOf("path", "content")
            )
        )
        all.put(
            fnDecl(
                name = "fs_edit",
                description = "Surgical search-and-replace edit on an existing file inside the workspace.",
                properties = JSONObject()
                    .put("path", prop("string", "Relative file path inside workspace"))
                    .put("old_text", prop("string", "Exact substring to find"))
                    .put("new_text", prop("string", "Replacement text"))
                    .put("replace_all", prop("boolean", "Replace all occurrences instead of requiring a single match (default false)")),
                required = listOf("path", "old_text", "new_text")
            )
        )
        all.put(
            fnDecl(
                name = "fs_search",
                description = "Search for a substring or keyword across text files in the workspace.",
                properties = JSONObject()
                    .put("query", prop("string", "Substring to search for"))
                    .put("path", prop("string", "Relative subdirectory or file (default '.')"))
                    .put("case_sensitive", prop("boolean", "Case-sensitive match (default false)")),
                required = listOf("query")
            )
        )
        all.put(
            fnDecl(
                name = "shell_exec",
                description = "Execute a non-interactive local shell command with timeout inside the workspace directory.",
                properties = JSONObject()
                    .put("command", prop("string", "Shell command line to run via sh -c"))
                    .put("cwd", prop("string", "Relative working directory inside workspace (default '.')")),
                required = listOf("command")
            )
        )
        all.put(
            fnDecl(
                name = "git_cmd",
                description = "Run a safe Git command (status, diff, log, show, branch, rev-parse) inside the workspace.",
                properties = JSONObject()
                    .put("subcommand", prop("string", "One of: status, diff, log, show, branch, rev-parse"))
                    .put("args", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", "Additional CLI arguments")),
                required = listOf("subcommand")
            )
        )
        all.put(
            fnDecl(
                name = "termux_api",
                description = "Invoke an allowlisted Termux:API command on Android (battery-status, clipboard-get, clipboard-set, toast, notification, wifi-connectioninfo, tts-speak).",
                properties = JSONObject()
                    .put("api", prop("string", "One of: battery-status, clipboard-get, clipboard-set, toast, notification, wifi-connectioninfo, tts-speak"))
                    .put("args", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", "Optional arguments"))
                    .put("stdin", prop("string", "Optional stdin text")),
                required = listOf("api")
            )
        )
        all.put(
            fnDecl(
                name = "self_adb",
                description = "Run an allowlisted local Android diagnostic/ADB subcommand (devices, getprop, dumpsys, pm, wm, logcat, input, am) with local Android shell fallback.",
                properties = JSONObject()
                    .put("action", prop("string", "One of: devices, getprop, dumpsys, pm, wm, logcat, input, am"))
                    .put("args", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", "Arguments for the action")),
                required = listOf("action")
            )
        )

        if (enabledTools == null) return all
        val filtered = JSONArray()
        for (i in 0 until all.length()) {
            val item = all.optJSONObject(i) ?: continue
            val fnName = item.optJSONObject("function")?.optString("name", "").orEmpty()
            if (fnName in enabledTools) {
                filtered.put(item)
            }
        }
        return filtered
    }

    fun execute(
        name: String,
        args: JSONObject,
        workspaceRoot: File,
        allowSdcardReadonly: Boolean,
        shellTimeoutSecs: Long = DEFAULT_SHELL_TIMEOUT_SECS,
        enabledTools: Set<String>? = null
    ): JSONObject {
        if (enabledTools != null && name !in enabledTools) {
            return JSONObject()
                .put("ok", false)
                .put("error", "Tool '$name' is disabled by user Work tool configuration.")
        }
        workspaceRoot.mkdirs()
        return try {
            when (name) {
                "fs_list" -> toolFsList(args, workspaceRoot, allowSdcardReadonly)
                "fs_read" -> toolFsRead(args, workspaceRoot, allowSdcardReadonly)
                "fs_write" -> toolFsWrite(args, workspaceRoot)
                "fs_edit" -> toolFsEdit(args, workspaceRoot)
                "fs_search" -> toolFsSearch(args, workspaceRoot, allowSdcardReadonly)
                "shell_exec" -> toolShellExec(args, workspaceRoot, shellTimeoutSecs)
                "git_cmd" -> toolGitCmd(args, workspaceRoot, shellTimeoutSecs)
                "termux_api" -> toolTermuxApi(args, workspaceRoot, shellTimeoutSecs)
                "self_adb" -> toolSelfAdb(args, workspaceRoot, shellTimeoutSecs)
                else -> JSONObject().put("ok", false).put("error", "Unknown harness tool: $name")
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: e.toString())
        }
    }

    private fun isAllowedCanonicalFile(
        candidate: File,
        workspaceRoot: File,
        allowSdcardReadonly: Boolean,
        writeAccess: Boolean
    ): Boolean {
        val cPath = runCatching { candidate.canonicalFile.path }.getOrNull() ?: return false
        val wsPath = runCatching { workspaceRoot.canonicalFile.path }.getOrNull() ?: return false
        if (cPath == wsPath || cPath.startsWith(wsPath + File.separator)) return true
        if (!writeAccess && allowSdcardReadonly &&
            (cPath == "/sdcard" || cPath.startsWith("/sdcard/") ||
                cPath == "/storage/emulated/0" || cPath.startsWith("/storage/emulated/0/"))
        ) {
            return true
        }
        return false
    }

    private fun resolvePath(
        rawPath: String,
        workspaceRoot: File,
        allowSdcardReadonly: Boolean,
        writeAccess: Boolean
    ): File {
        val trimmed = rawPath.trim().ifEmpty { "." }
        val wsCanonical = workspaceRoot.canonicalFile

        if (!writeAccess && allowSdcardReadonly &&
            (trimmed.startsWith("/sdcard") || trimmed.startsWith("/storage/emulated/0"))
        ) {
            val f = File(trimmed).canonicalFile
            val p = f.path
            if (p == "/sdcard" || p.startsWith("/sdcard/") ||
                p == "/storage/emulated/0" || p.startsWith("/storage/emulated/0/")
            ) {
                return f
            }
            throw IllegalArgumentException("Read-only external path must stay within /sdcard")
        }

        val candidate = if (File(trimmed).isAbsolute) {
            File(trimmed).canonicalFile
        } else {
            File(wsCanonical, trimmed).canonicalFile
        }

        val wsPrefix = wsCanonical.path
        if (candidate.path != wsPrefix && !candidate.path.startsWith(wsPrefix + File.separator)) {
            throw IllegalArgumentException("Path escapes workspace root: $rawPath")
        }
        return candidate
    }

    private fun relativeDisplayPath(file: File, workspaceRoot: File): String {
        val wsPath = workspaceRoot.canonicalFile.path
        val fPath = file.canonicalFile.path
        return if (fPath == wsPath) {
            "."
        } else if (fPath.startsWith(wsPath + File.separator)) {
            fPath.substring(wsPath.length + 1).replace('\\', '/')
        } else {
            fPath
        }
    }

    private fun toolFsList(
        args: JSONObject,
        workspaceRoot: File,
        allowSdcardReadonly: Boolean
    ): JSONObject {
        val rel = args.optString("path", ".")
        val recursive = args.optBoolean("recursive", false)
        val dir = resolvePath(rel, workspaceRoot, allowSdcardReadonly, writeAccess = false)
        if (!dir.exists()) {
            return JSONObject().put("ok", false).put("error", "Path does not exist: $rel")
        }
        val entries = JSONArray()
        val maxEntries = 200
        fun walk(current: File, depth: Int) {
            if (entries.length() >= maxEntries || depth > 3) return
            val children = current.listFiles()?.sortedBy { it.name } ?: return
            for (child in children) {
                if (entries.length() >= maxEntries) break
                if (child.name == ".git" || child.name == "target" || child.name == "build") continue
                if (!isAllowedCanonicalFile(child, workspaceRoot, allowSdcardReadonly, writeAccess = false)) continue
                val item = JSONObject()
                    .put("path", relativeDisplayPath(child, workspaceRoot))
                    .put("type", if (child.isDirectory) "dir" else "file")
                    .put("size", if (child.isFile) child.length() else 0L)
                entries.put(item)
                if (recursive && child.isDirectory) {
                    walk(child, depth + 1)
                }
            }
        }
        if (dir.isDirectory) {
            walk(dir, 1)
        } else {
            entries.put(
                JSONObject()
                    .put("path", relativeDisplayPath(dir, workspaceRoot))
                    .put("type", "file")
                    .put("size", dir.length())
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("path", relativeDisplayPath(dir, workspaceRoot))
            .put("count", entries.length())
            .put("entries", entries)
    }

    private fun toolFsRead(
        args: JSONObject,
        workspaceRoot: File,
        allowSdcardReadonly: Boolean
    ): JSONObject {
        val rel = args.optString("path", "")
        if (rel.isBlank()) return JSONObject().put("ok", false).put("error", "path is required")
        val file = resolvePath(rel, workspaceRoot, allowSdcardReadonly, writeAccess = false)
        if (!file.exists() || !file.isFile) {
            return JSONObject().put("ok", false).put("error", "File not found: $rel")
        }
        if (file.length() > MAX_FILE_READ_BYTES) {
            return JSONObject().put(
                "ok",
                false
            ).put("error", "File exceeds $MAX_FILE_READ_BYTES bytes limit (${file.length()} bytes)")
        }
        val content = file.readText(Charsets.UTF_8)
        val lines = content.lines()
        val startLine = args.optInt("start_line", 1).coerceAtLeast(1)
        val endLine = args.optInt("end_line", lines.size).coerceIn(startLine, lines.size.coerceAtLeast(1))
        val slice = if (lines.isEmpty()) {
            ""
        } else {
            lines.subList(startLine - 1, endLine).joinToString("\n")
        }
        return JSONObject()
            .put("ok", true)
            .put("path", relativeDisplayPath(file, workspaceRoot))
            .put("total_lines", lines.size)
            .put("start_line", startLine)
            .put("end_line", endLine)
            .put("content", slice)
    }

    private fun toolFsWrite(
        args: JSONObject,
        workspaceRoot: File
    ): JSONObject {
        val rel = args.optString("path", "")
        if (rel.isBlank()) return JSONObject().put("ok", false).put("error", "path is required")
        val content = args.optString("content", "")
        val append = args.optBoolean("append", false)
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FILE_WRITE_BYTES) {
            return JSONObject().put("ok", false).put("error", "Content exceeds $MAX_FILE_WRITE_BYTES bytes")
        }
        val file = resolvePath(rel, workspaceRoot, allowSdcardReadonly = false, writeAccess = true)
        if (append && file.exists()) {
            val totalAfter = file.length() + bytes.size.toLong()
            if (totalAfter > MAX_FILE_WRITE_BYTES) {
                return JSONObject()
                    .put("ok", false)
                    .put("error", "Appending would exceed $MAX_FILE_WRITE_BYTES bytes total limit ($totalAfter bytes)")
            }
        }
        file.parentFile?.mkdirs()
        if (append && file.exists()) {
            file.appendText(content, Charsets.UTF_8)
        } else {
            file.writeText(content, Charsets.UTF_8)
        }
        val relDisplay = relativeDisplayPath(file, workspaceRoot)
        return JSONObject()
            .put("ok", true)
            .put("path", relDisplay)
            .put("bytes", file.length())
            .put("mode", if (append) "append" else "write")
    }

    private fun toolFsEdit(
        args: JSONObject,
        workspaceRoot: File
    ): JSONObject {
        val rel = args.optString("path", "")
        val oldText = args.optString("old_text", "")
        val newText = args.optString("new_text", "")
        val replaceAll = args.optBoolean("replace_all", false)
        if (rel.isBlank() || oldText.isEmpty()) {
            return JSONObject().put("ok", false).put("error", "path and non-empty old_text are required")
        }
        val file = resolvePath(rel, workspaceRoot, allowSdcardReadonly = false, writeAccess = true)
        if (!file.exists() || !file.isFile) {
            return JSONObject().put("ok", false).put("error", "File not found: $rel")
        }
        if (file.length() > MAX_FILE_WRITE_BYTES) {
            return JSONObject().put("ok", false).put("error", "File exceeds $MAX_FILE_WRITE_BYTES bytes edit limit")
        }
        val original = file.readText(Charsets.UTF_8)
        val occurrences = countOccurrences(original, oldText)
        if (occurrences == 0) {
            return JSONObject().put("ok", false).put("error", "old_text not found in $rel")
        }
        if (occurrences > 1 && !replaceAll) {
            return JSONObject().put(
                "ok",
                false
            ).put("error", "old_text matched $occurrences times in $rel; set replace_all=true or provide more context")
        }
        val updated = if (replaceAll) {
            original.replace(oldText, newText)
        } else {
            original.replaceFirst(oldText, newText)
        }
        val updatedBytes = updated.toByteArray(Charsets.UTF_8)
        if (updatedBytes.size > MAX_FILE_WRITE_BYTES) {
            return JSONObject()
                .put("ok", false)
                .put("error", "Edited content exceeds $MAX_FILE_WRITE_BYTES bytes limit (${updatedBytes.size} bytes)")
        }
        file.writeText(updated, Charsets.UTF_8)
        val relDisplay = relativeDisplayPath(file, workspaceRoot)
        return JSONObject()
            .put("ok", true)
            .put("path", relDisplay)
            .put("replaced", if (replaceAll) occurrences else 1)
            .put("bytes", file.length())
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        var count = 0
        var idx = 0
        while (true) {
            val found = haystack.indexOf(needle, idx)
            if (found < 0) break
            count++
            idx = found + needle.length
        }
        return count
    }

    private fun toolFsSearch(
        args: JSONObject,
        workspaceRoot: File,
        allowSdcardReadonly: Boolean
    ): JSONObject {
        val query = args.optString("query", "")
        if (query.isBlank()) return JSONObject().put("ok", false).put("error", "query is required")
        val rel = args.optString("path", ".")
        val caseSensitive = args.optBoolean("case_sensitive", false)
        val root = resolvePath(rel, workspaceRoot, allowSdcardReadonly, writeAccess = false)
        if (!root.exists()) {
            return JSONObject().put("ok", false).put("error", "Search path does not exist: $rel")
        }
        val matches = JSONArray()
        val maxMatches = 80
        fun searchFile(f: File) {
            if (matches.length() >= maxMatches || !f.isFile || f.length() > MAX_FILE_READ_BYTES) return
            if (!isAllowedCanonicalFile(f, workspaceRoot, allowSdcardReadonly, writeAccess = false)) return
            val lines = runCatching { f.readLines(Charsets.UTF_8) }.getOrNull() ?: return
            for ((idx, line) in lines.withIndex()) {
                if (matches.length() >= maxMatches) break
                val hit = if (caseSensitive) {
                    line.contains(query)
                } else {
                    line.contains(query, ignoreCase = true)
                }
                if (hit) {
                    matches.put(
                        JSONObject()
                            .put("path", relativeDisplayPath(f, workspaceRoot))
                            .put("line", idx + 1)
                            .put("text", line.take(240))
                    )
                }
            }
        }
        if (root.isFile) {
            searchFile(root)
        } else {
            root.walkTopDown()
                .onEnter { dir ->
                    dir.name != ".git" &&
                        dir.name != "target" &&
                        dir.name != "build" &&
                        isAllowedCanonicalFile(dir, workspaceRoot, allowSdcardReadonly, writeAccess = false)
                }
                .forEach { f ->
                    if (matches.length() < maxMatches) searchFile(f)
                }
        }
        return JSONObject()
            .put("ok", true)
            .put("query", query)
            .put("count", matches.length())
            .put("matches", matches)
    }

    private fun isBlockedShellCommand(cmd: String): String? {
        val lower = cmd.lowercase()
        val blockedPatterns = listOf(
            "rm -rf /",
            "mkfs",
            "dd if=/dev/zero",
            ":(){ :|:& };:",
            "reboot",
            "shutdown"
        )
        for (pat in blockedPatterns) {
            if (lower.contains(pat)) return "Blocked dangerous command pattern: $pat"
        }
        return null
    }

    private fun toolShellExec(
        args: JSONObject,
        workspaceRoot: File,
        timeoutSecs: Long
    ): JSONObject {
        val command = args.optString("command", "").trim()
        if (command.isBlank()) return JSONObject().put("ok", false).put("error", "command is required")
        isBlockedShellCommand(command)?.let { reason ->
            return JSONObject().put("ok", false).put("error", reason)
        }
        val cwdRel = args.optString("cwd", ".")
        val cwd = resolvePath(cwdRel, workspaceRoot, allowSdcardReadonly = false, writeAccess = false)
        return runProcess(listOf("sh", "-c", command), cwd, null, timeoutSecs)
    }

    private fun toolGitCmd(
        args: JSONObject,
        workspaceRoot: File,
        timeoutSecs: Long
    ): JSONObject {
        val sub = args.optString("subcommand", "").trim()
        val allowed = setOf("status", "diff", "log", "show", "branch", "rev-parse")
        if (sub !in allowed) {
            return JSONObject().put("ok", false).put("error", "Unsupported git subcommand: $sub")
        }
        val cmd = mutableListOf("git", sub)
        val extra = args.optJSONArray("args")
        if (extra != null) {
            for (i in 0 until extra.length()) {
                val a = extra.optString(i, "")
                if (a.isNotBlank()) cmd.add(a)
            }
        }
        return runProcess(cmd, workspaceRoot, null, timeoutSecs)
    }

    private fun toolTermuxApi(
        args: JSONObject,
        workspaceRoot: File,
        timeoutSecs: Long
    ): JSONObject {
        val api = args.optString("api", "").trim()
        val allowed = setOf(
            "battery-status",
            "clipboard-get",
            "clipboard-set",
            "toast",
            "notification",
            "wifi-connectioninfo",
            "tts-speak"
        )
        if (api !in allowed) {
            return JSONObject().put("ok", false).put("error", "Unsupported termux_api: $api")
        }
        val binName = "termux-$api"
        val cmd = mutableListOf(binName)
        val extra = args.optJSONArray("args")
        if (extra != null) {
            for (i in 0 until extra.length()) {
                val a = extra.optString(i, "")
                if (a.isNotBlank()) cmd.add(a)
            }
        }
        val stdinText = args.optString("stdin", "").takeIf { it.isNotEmpty() }
        val res = runProcess(cmd, workspaceRoot, stdinText, timeoutSecs)
        if (res.optBoolean("ok", false)) return res

        val baseErr = res.optString("error", "").ifBlank {
            res.optString("output", "termux-api binary ($binName) failed or unavailable")
        }
        return JSONObject()
            .put("ok", false)
            .put("api", api)
            .put("binary", binName)
            .put("device_model", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("sdk_int", Build.VERSION.SDK_INT)
            .put("error", "$binName failed: $baseErr")
    }

    private fun toolSelfAdb(
        args: JSONObject,
        workspaceRoot: File,
        timeoutSecs: Long
    ): JSONObject {
        val action = args.optString("action", "").trim()
        val allowed = setOf("devices", "getprop", "dumpsys", "pm", "wm", "logcat", "input", "am")
        if (action !in allowed) {
            return JSONObject().put("ok", false).put("error", "Unsupported self_adb action: $action")
        }
        val extra = mutableListOf<String>()
        val arr = args.optJSONArray("args")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val a = arr.optString(i, "")
                if (a.isNotBlank()) extra.add(a)
            }
        }
        val directCmd = when (action) {
            "devices" -> listOf("sh", "-c", "echo 'local-android-sandbox\tdevice'")
            "getprop" -> listOf("getprop") + extra
            "dumpsys" -> listOf("dumpsys") + extra
            "pm" -> listOf("pm") + extra
            "wm" -> listOf("wm") + extra
            "logcat" -> listOf("logcat", "-d") + extra
            "input" -> listOf("input") + extra
            "am" -> listOf("am") + extra
            else -> listOf("getprop")
        }
        return runProcess(directCmd, workspaceRoot, null, timeoutSecs)
    }

    /**
     * Deadlock-free process runner: drains combined stdout/stderr concurrently on a background thread
     * before `waitFor`, capping buffered bytes at `MAX_CMD_OUTPUT_BYTES` to prevent memory exhaustion.
     */
    private fun runProcess(
        cmd: List<String>,
        cwd: File,
        stdinText: String?,
        timeoutSecs: Long
    ): JSONObject {
        return try {
            val pb = ProcessBuilder(cmd)
                .directory(cwd)
                .redirectErrorStream(true)
            val proc = pb.start()

            val outBuffer = ByteArrayOutputStream()
            var truncated = false
            val drainThread = Thread {
                runCatching {
                    val buf = ByteArray(4096)
                    proc.inputStream.use { input ->
                        while (true) {
                            val read = input.read(buf)
                            if (read < 0) break
                            if (outBuffer.size() < MAX_CMD_OUTPUT_BYTES) {
                                val remaining = MAX_CMD_OUTPUT_BYTES - outBuffer.size()
                                val toWrite = read.coerceAtMost(remaining)
                                outBuffer.write(buf, 0, toWrite)
                                if (read > remaining) truncated = true
                            } else {
                                truncated = true
                            }
                        }
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }

            runCatching {
                if (stdinText != null) {
                    proc.outputStream.use { it.write(stdinText.toByteArray(Charsets.UTF_8)) }
                } else {
                    proc.outputStream.close()
                }
            }

            val clampedTimeout = timeoutSecs.coerceIn(2L, 120L)
            val finished = proc.waitFor(clampedTimeout, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                drainThread.join(1000L)
                val partialOut = String(outBuffer.toByteArray(), Charsets.UTF_8).trimEnd()
                return JSONObject()
                    .put("ok", false)
                    .put("output", partialOut)
                    .put("truncated", truncated)
                    .put("error", "Process timed out after ${clampedTimeout}s")
            }

            drainThread.join(2000L)
            val rawOut = String(outBuffer.toByteArray(), Charsets.UTF_8)
            val outText = if (truncated) "$rawOut\n...[truncated]..." else rawOut
            val code = proc.exitValue()
            JSONObject()
                .put("ok", code == 0)
                .put("exit_code", code)
                .put("output", outText.trimEnd())
                .put("truncated", truncated)
        } catch (e: Exception) {
            JSONObject()
                .put("ok", false)
                .put("error", e.message ?: e.toString())
        }
    }

    private fun prop(type: String, description: String): JSONObject =
        JSONObject().put("type", type).put("description", description)

    private fun fnDecl(
        name: String,
        description: String,
        properties: JSONObject,
        required: List<String>
    ): JSONObject {
        val params = JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("required", JSONArray(required))
        return JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put("description", description)
                    .put("parameters", params)
            )
    }
}
