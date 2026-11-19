package com.uronic0x.fluorine

import com.uronic0x.fluorine.bridge.FluorineBridge
import com.uronic0x.fluorine.protocol.ChatAttachment
import com.uronic0x.fluorine.protocol.ChatMessage
import com.uronic0x.fluorine.protocol.CitationPoint
import com.uronic0x.fluorine.protocol.ContextTurn
import com.uronic0x.fluorine.protocol.ConversationSession
import com.uronic0x.fluorine.protocol.CoreProtocol
import com.uronic0x.fluorine.protocol.LocalToolCallRecord
import com.uronic0x.fluorine.protocol.StreamUiEvent
import com.uronic0x.fluorine.protocol.WorkToolsConfig
import com.uronic0x.fluorine.ui.components.LatexMathParser
import com.uronic0x.fluorine.ui.components.MathNode
import com.uronic0x.fluorine.ui.components.MdBlock
import com.uronic0x.fluorine.ui.components.injectInlineCitations
import com.uronic0x.fluorine.ui.components.parseMarkdownBlocks
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class FluorineProtocolAndToolsTest {

    private open class StubNativeBackend(override val isLoaded: Boolean = true) :
        FluorineBridge.NativeBackend {
        override fun getMeta(workspacePath: String, allowSdcardReadonly: Boolean): String =
            JSONObject().put("ok", true).put("engine", "rust-jni").toString()

        override fun fetchModels(
            apiKey: String,
            baseUrl: String,
            connectTimeoutSec: Int,
            readTimeoutSec: Int
        ): String = JSONObject().put("ok", true).put("models", JSONArray()).toString()

        override fun uploadFile(
            apiKey: String,
            baseUrl: String,
            filePath: String,
            filename: String,
            mimeType: String,
            purpose: String,
            allowedRootsNewline: String,
            connectTimeoutSec: Int,
            readTimeoutSec: Int
        ): String = JSONObject().put("ok", true).put("id", "file-stub-1").toString()

        override fun streamChat(
            requestId: Long,
            apiKey: String,
            requestJson: String,
            workspacePath: String,
            allowSdcardReadonly: Boolean,
            callback: FluorineBridge.JniStreamCallback
        ) = Unit

        override fun cancelStream(requestId: Long): Boolean = true

        override fun executeLocalTool(
            name: String,
            argumentsJson: String,
            workspacePath: String,
            allowSdcardReadonly: Boolean
        ): String = JSONObject().put("ok", true).toString()

        override fun buildUpstreamPayload(requestJson: String): String =
            JSONObject().put("ok", true).toString()
    }

    @Test
    fun workToolsConfigMatches19RustCanonicalToolsAndMapsLegacyAliases() {
        val allEnabled = WorkToolsConfig(
            deterministic = true,
            workspaceRead = true,
            workspaceWrite = true,
            git = true,
            shellExec = true,
            deviceControl = true
        )
        assertEquals(19, WorkToolsConfig.ALL_DESCRIPTORS.size)
        assertEquals(19, allEnabled.enabledToolNames().size)
        assertTrue("device_probe" in allEnabled.enabledToolNames())
        assertTrue("git_inspect" in allEnabled.enabledToolNames())
        assertTrue("termux_device" in allEnabled.enabledToolNames())
        assertTrue("android_ui" in allEnabled.enabledToolNames())

        // Legacy alias normalization
        assertEquals("calc", WorkToolsConfig.canonicalToolName("calc_eval"))
        assertEquals("stats", WorkToolsConfig.canonicalToolName("stats_summary"))
        assertEquals("git_inspect", WorkToolsConfig.canonicalToolName("git_cmd"))
        assertEquals("termux_device", WorkToolsConfig.canonicalToolName("termux_api"))
        assertEquals("android_ui", WorkToolsConfig.canonicalToolName("self_adb"))

        val cfg = WorkToolsConfig(
            deterministic = true,
            workspaceRead = true,
            workspaceWrite = false,
            git = false,
            shellExec = true,
            deviceControl = false,
            disabledTools = setOf("stats_summary")
        )
        val enabled = cfg.enabledToolNames()
        assertTrue("calc" in enabled)
        assertTrue(cfg.isToolEnabled("calc_eval"))
        assertTrue("shell_exec" in enabled)
        assertTrue("fs_read" in enabled)
        assertFalse("stats" in enabled)
        assertFalse(cfg.isToolEnabled("stats_summary"))
        assertFalse("fs_write" in enabled)
        assertFalse(cfg.isToolEnabled("git_cmd"))
        assertFalse(cfg.isToolEnabled("termux_api"))
        assertFalse(cfg.isToolEnabled("self_adb"))

        val roundTripped = WorkToolsConfig.fromJson(cfg.toJson())
        assertEquals(setOf("stats"), roundTripped.disabledTools)
        assertFalse(roundTripped.workspaceWrite)
        assertTrue(roundTripped.shellExec)
    }

    @Test
    fun validateBaseUrlEnforcesHttpsOrLoopbackPortAndRejectsUnsafeInputs() {
        assertEquals(
            "https://api.deepseek.com",
            CoreProtocol.validateBaseUrl("https://api.deepseek.com/").getOrThrow()
        )
        assertEquals(
            "https://proxy.example.com:8443/v1",
            CoreProtocol.validateBaseUrl("  https://proxy.example.com:8443/v1/ ").getOrThrow()
        )
        assertEquals(
            "https://[2001:db8::1]:8443/v1",
            CoreProtocol.validateBaseUrl("https://[2001:db8::1]:8443/v1").getOrThrow()
        )
        assertEquals(
            "http://127.0.0.1:8765",
            CoreProtocol.validateBaseUrl("http://127.0.0.1:8765/").getOrThrow()
        )
        assertEquals(
            "http://localhost:8080/v1",
            CoreProtocol.validateBaseUrl("http://localhost:8080/v1").getOrThrow()
        )

        val invalidUrls = listOf(
            "",
            "http://api.deepseek.com",
            "http://127.0.0.1",
            "http://127.0.0.1.evil.com:8080",
            "http://localhost.evil.com:8080",
            "https://user:pass@api.deepseek.com",
            "https://api.deepseek.com?foo=bar",
            "https://api.deepseek.com#frag",
            "https://api.deepseek.com/v1/../v2",
            "https://api.deepseek.com:99999",
            "ftp://api.deepseek.com"
        )
        for (bad in invalidUrls) {
            assertTrue("Expected failure for: $bad", CoreProtocol.validateBaseUrl(bad).isFailure)
        }
    }

    @Test
    fun sanitizeAttachmentNeutralizesHeaderInjectionAndImportedPaths() {
        val raw = ChatAttachment(
            id = "att-1",
            name = "../../secret\r\nX-Inject: 1\"bad.pdf",
            mimeType = "application/pdf; boundary=evil\r\nX-Bad: 1",
            kind = "executable",
            localPath = "/data/data/com.uronic0x.fluorine/shared_prefs/fluorine_prefs.xml",
            sizeBytes = -50L,
            fileId = "file-123\r\n",
            parsePurpose = "evil_purpose\r\n"
        )

        val imported = CoreProtocol.sanitizeAttachment(raw, stripLocalPath = true)
        assertFalse(imported.name.contains("\r"))
        assertFalse(imported.name.contains("\n"))
        assertFalse(imported.name.contains("/"))
        assertFalse(imported.name.contains("\""))
        assertEquals("application/pdf", imported.mimeType)
        assertEquals("document", imported.kind)
        assertEquals("user_data", imported.parsePurpose)
        assertEquals("", imported.localPath)
        assertEquals(0L, imported.sizeBytes)
        assertEquals("file-123", imported.fileId)

        val invalidMime = CoreProtocol.sanitizeAttachment(raw.copy(mimeType = "text/plain\r\nInject: 1"))
        assertEquals("application/octet-stream", invalidMime.mimeType)
    }

    @Test
    fun deserializeSessionsStoreCheckedRejectsOversizedAndMalformedPayloads() {
        assertTrue(CoreProtocol.deserializeSessionsStoreChecked("{bad json").isFailure)

        val oversized = " ".repeat(CoreProtocol.MAX_STORE_JSON_BYTES + 64)
        val res = CoreProtocol.deserializeSessionsStoreChecked(oversized)
        assertTrue(res.isFailure)
    }

    @Test
    fun fluorineBridgeEnforcesRustOnlyWithNoFallbackWhenNativeMissing() {
        try {
            FluorineBridge.setBackendForTesting(StubNativeBackend(isLoaded = false))
            assertFalse(FluorineBridge.isRustJniLoaded)

            val meta = JSONObject(FluorineBridge.engineInfoJson())
            assertFalse(meta.getBoolean("ok"))
            assertEquals("rust-jni-missing", meta.getString("engine"))

            val chatRes = CoreProtocol.parseEngineTurnResult(
                FluorineBridge.chatCompleteStreamingJson(
                    requestId = 101L,
                    requestJson = """{"api_key":"sk-test","model":"deepseek-flash","messages":[]}""",
                    onEvent = {}
                )
            )
            assertFalse(chatRes.ok)
            assertTrue(chatRes.error.contains("libfluorine.so"))
            assertFalse(FluorineBridge.cancelStream(101L))
        } finally {
            FluorineBridge.setBackendForTesting(null)
        }
    }

    @Test
    fun fluorineBridgeRoutesStreamChatAndCancelStrictlyToRustBackend() {
        val capturedApiKey = AtomicReference("")
        val capturedReqJson = AtomicReference("")
        val cancelCalled = AtomicBoolean(false)

        val fakeBackend = object : StubNativeBackend(isLoaded = true) {
            override fun streamChat(
                requestId: Long,
                apiKey: String,
                requestJson: String,
                workspacePath: String,
                allowSdcardReadonly: Boolean,
                callback: FluorineBridge.JniStreamCallback
            ) {
                capturedApiKey.set(apiKey)
                capturedReqJson.set(requestJson)
                assertTrue(
                    callback.onEvent(
                        """{"choices":[{"delta":{"reasoning_content":"先分析"}}]}"""
                    )
                )
                assertTrue(
                    callback.onEvent(
                        """{"choices":[{"delta":{"content":"最终回答"},"finish_reason":"stop"}],"usage":{"prompt_tokens":12,"completion_tokens":8,"server_tool_use":{"tool_search":2,"tool_fetch":1}}}"""
                    )
                )
                assertTrue(callback.onEvent("[DONE]"))
            }

            override fun cancelStream(requestId: Long): Boolean {
                cancelCalled.set(true)
                return true
            }
        }

        val events = mutableListOf<StreamUiEvent>()
        try {
            FluorineBridge.setBackendForTesting(fakeBackend)
            assertTrue(FluorineBridge.isRustJniLoaded)

            val raw = FluorineBridge.chatCompleteStreamingJson(
                requestId = 202L,
                requestJson = JSONObject()
                    .put("api_key", "sk-secret")
                    .put("base_url", "https://api.deepseek.com/")
                    .put("model", "deepseek-flash")
                    .put("stage", "chat")
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "hi")))
                    .toString(),
                onEvent = { events.add(it) }
            )
            val parsed = CoreProtocol.parseEngineTurnResult(raw)
            assertTrue(parsed.ok)
            assertEquals("sk-secret", capturedApiKey.get())
            val forwarded = JSONObject(capturedReqJson.get())
            assertFalse("api_key should be stripped from requestJson body", forwarded.has("api_key"))
            assertEquals("https://api.deepseek.com", forwarded.getString("base_url"))
            assertEquals("先分析", parsed.thinking)
            assertEquals("最终回答", parsed.content)
            assertEquals(3, parsed.serverToolUseCount)
            assertTrue(events.any { it is StreamUiEvent.ThinkingDelta && it.delta == "先分析" })
            assertTrue(events.any { it is StreamUiEvent.ContentDelta && it.delta == "最终回答" })

            assertTrue(FluorineBridge.cancelStream(202L))
            assertTrue(cancelCalled.get())
        } finally {
            FluorineBridge.setBackendForTesting(null)
        }
    }

    @Test
    fun fluorineBridgeIgnoresNullContentDeltasInStreaming() {
        val fakeBackend = object : StubNativeBackend(isLoaded = true) {
            override fun streamChat(
                requestId: Long,
                apiKey: String,
                requestJson: String,
                workspacePath: String,
                allowSdcardReadonly: Boolean,
                callback: FluorineBridge.JniStreamCallback
            ) {
                // 上游 SSE 在推理阶段常发 content:null；org.json optString 会把它变成字面量 "null"
                assertTrue(callback.onEvent("""{"choices":[{"delta":{"reasoning_content":null,"content":null}}]}"""))
                assertTrue(callback.onEvent("""{"choices":[{"delta":{"content":null}}]}"""))
                assertTrue(callback.onEvent("""{"choices":[{"delta":{"reasoning_content":"先分析"}}]}"""))
                assertTrue(callback.onEvent("""{"choices":[{"delta":{"content":"最终回答"},"finish_reason":"stop"}]}"""))
                assertTrue(callback.onEvent("[DONE]"))
            }
        }

        try {
            FluorineBridge.setBackendForTesting(fakeBackend)
            val raw = FluorineBridge.chatCompleteStreamingJson(
                requestId = 203L,
                requestJson = JSONObject()
                    .put("api_key", "sk-test")
                    .put("base_url", "https://api.deepseek.com/")
                    .put("model", "deepseek-flash")
                    .put("stage", "chat")
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "hi")))
                    .toString(),
                onEvent = {}
            )
            val parsed = CoreProtocol.parseEngineTurnResult(raw)
            assertTrue(parsed.ok)
            assertEquals("先分析", parsed.thinking)
            assertEquals("最终回答", parsed.content)
            assertFalse(parsed.content.contains("null"))
            assertFalse(parsed.thinking.contains("null"))
        } finally {
            FluorineBridge.setBackendForTesting(null)
        }
    }

    @Test
    fun fluorineBridgeUploadRejectsInvalidBaseUrlAndUninitializedManagedRoots() {
        val tempRoot = Files.createTempDirectory("fluorine_att_root").toFile()
        val sampleFile = File(tempRoot, "doc.pdf").apply { writeText("pdf") }
        val uploadedRoots = AtomicReference("")
        val fakeBackend = object : StubNativeBackend(isLoaded = true) {
            override fun uploadFile(
                apiKey: String,
                baseUrl: String,
                filePath: String,
                filename: String,
                mimeType: String,
                purpose: String,
                allowedRootsNewline: String,
                connectTimeoutSec: Int,
                readTimeoutSec: Int
            ): String {
                uploadedRoots.set(allowedRootsNewline)
                return JSONObject().put("ok", true).put("id", "file-ok-1").toString()
            }
        }
        try {
            FluorineBridge.setBackendForTesting(fakeBackend)
            FluorineBridge.setManagedAttachmentRootsForTesting(emptyList())

            val att = ChatAttachment(
                id = "a1",
                name = "doc.pdf",
                mimeType = "application/pdf",
                kind = "document",
                localPath = sampleFile.absolutePath
            )
            val noRootsResp = JSONObject(
                FluorineBridge.uploadAttachmentFileJson("sk-test", "https://api.deepseek.com", att)
            )
            assertFalse(noRootsResp.getBoolean("ok"))

            FluorineBridge.setManagedAttachmentRootsForTesting(listOf(tempRoot))
            val badUrlResp = JSONObject(
                FluorineBridge.uploadAttachmentFileJson("sk-test", "http://evil.example.com", att)
            )
            assertFalse(badUrlResp.getBoolean("ok"))

            val okResp = JSONObject(
                FluorineBridge.uploadAttachmentFileJson("sk-test", "https://api.deepseek.com", att)
            )
            assertTrue(okResp.getBoolean("ok"))
            assertEquals("file-ok-1", okResp.getString("id"))
            assertTrue(uploadedRoots.get().contains(tempRoot.canonicalPath))
        } finally {
            FluorineBridge.setBackendForTesting(null)
            FluorineBridge.setManagedAttachmentRootsForTesting(emptyList())
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun parseEngineTurnResultPreservesPartialOutputAndSupportsObjectError() {
        val raw = JSONObject()
            .put("ok", false)
            .put("error", JSONObject().put("code", "content_filter").put("message", "Upstream terminated with content_filter"))
            .put("finish_reason", "content_filter")
            .put("incomplete_notice", "生成因触发内容安全审核被提前终止（已完整保留已输出的思考与正文）")
            .put("thinking", "已输出思考过程")
            .put("content", "已流出的半截回答内容")
            .put("sources", JSONArray())
            .put("local_tool_calls", JSONArray())
            .put("usage", JSONObject().put("prompt_tokens", 10).put("completion_tokens", 20))
            .toString()

        val parsed = CoreProtocol.parseEngineTurnResult(raw)
        assertFalse(parsed.ok)
        assertEquals("Upstream terminated with content_filter", parsed.error)
        assertEquals("content_filter", parsed.finishReason)
        assertEquals("已输出思考过程", parsed.thinking)
        assertEquals("已流出的半截回答内容", parsed.content)
        assertTrue(parsed.incompleteNotice.contains("内容安全审核"))
        assertTrue(parsed.usageText.contains("输入 10"))
    }

    @Test
    fun multimodalCapabilitiesWork() {
        assertTrue(CoreProtocol.supportsMultimodal("deepseek-flash", CoreProtocol.DEFAULT_MODEL_CAPABILITIES))
        assertFalse(CoreProtocol.supportsMultimodal("deepseek-v4-pro", CoreProtocol.DEFAULT_MODEL_CAPABILITIES))
    }

    @Test
    fun extractWorkspaceArtifactsAndMultiSessionRoundTrip() {
        val toolCalls = listOf(
            LocalToolCallRecord(
                round = 1,
                id = "c1",
                name = "fs_write",
                arguments = """{"path":"reports/summary.md","content":"# Summary"}""",
                output = """{"ok":true,"path":"reports/summary.md","bytes":128,"mode":"write"}"""
            ),
            LocalToolCallRecord(
                round = 2,
                id = "c2",
                name = "calc",
                arguments = """{"expr":"1+1"}""",
                output = """{"ok":true,"result":2}"""
            )
        )
        val artifacts = CoreProtocol.extractWorkspaceArtifacts(toolCalls)
        assertEquals(1, artifacts.size)
        assertEquals("reports/summary.md", artifacts[0].relativePath)
        assertEquals("summary.md", artifacts[0].fileName)
        assertEquals(128L, artifacts[0].bytes)

        val attachment = ChatAttachment(
            id = "att1",
            name = "photo.jpg",
            mimeType = "image/jpeg",
            kind = "image",
            localPath = "/tmp/photo.jpg",
            sizeBytes = 1024L
        )
        val session1 = ConversationSession(
            id = "s1",
            title = "公式推导",
            mode = "chat",
            createdAt = 1000L,
            updatedAt = 2000L,
            chatMessages = listOf(
                ChatMessage(id = "m1", role = "user", text = "推导高斯积分", attachments = listOf(attachment))
            ),
            contextHistory = listOf(ContextTurn("user", "推导高斯积分", attachments = listOf(attachment)))
        )
        val session2 = ConversationSession(
            id = "s2",
            title = "生成报告",
            mode = "work",
            createdAt = 1500L,
            updatedAt = 3000L,
            chatMessages = listOf(
                ChatMessage(
                    id = "m2",
                    role = "assistant",
                    text = "已生成报告",
                    localToolCalls = toolCalls,
                    incompleteNotice = "已截断"
                )
            ),
            contextHistory = listOf(
                ContextTurn(
                    role = "assistant",
                    content = "已生成报告",
                    subturnMessages = listOf("""{"role":"tool","tool_call_id":"c1","content":"{}"}"""),
                    reasoningContent = "思考内容"
                )
            )
        )

        val json = CoreProtocol.serializeSessionsStore(listOf(session1, session2), "s2")
        val (restored, activeId) = CoreProtocol.deserializeSessionsStore(json)
        assertEquals("s2", activeId)
        assertEquals(2, restored.size)
        assertEquals(1, restored[0].chatMessages[0].attachments.size)
        assertEquals("生成报告", restored[1].title)
        assertEquals(2, restored[1].chatMessages[0].localToolCalls.size)
        assertEquals("已截断", restored[1].chatMessages[0].incompleteNotice)
        assertEquals("思考内容", restored[1].contextHistory[0].reasoningContent)
    }

    @Test
    fun markdownLatexAndCodePointCitationInjectionWork() {
        val sample = "😀测试引用句" // emoji is 2 UTF-16 code units, 1 Unicode code point!
        val cited = injectInlineCitations(
            sample,
            listOf(
                CitationPoint(
                    offset = 5,
                    indexes = listOf(1),
                    startIndex = 0,
                    endIndex = 5,
                    url = "https://example.com"
                )
            )
        )
        assertEquals("😀测试引用[[1]](https://example.com)句", cited)

        val md = """
            # 标题一
            这是段落，包含行内公式 $\frac{a+b}{c}$ 与 **加粗**。
            
            $$
            \int_{0}^{\infty} \sqrt{x^2 + 1} dx
            $$
            
            | 指标 | 数值 |
            | --- | --- |
            | 命中率 | 92% |
        """.trimIndent()

        val blocks = parseMarkdownBlocks(md, enableMarkdown = true, enableLatex = true)
        assertTrue(blocks.any { it is MdBlock.Heading })
        assertTrue(blocks.any { it is MdBlock.MathBlock })
        assertTrue(blocks.any { it is MdBlock.TableBlock })

        val mathNode = LatexMathParser("""\frac{\sqrt{x^2 + 1}}{\alpha + \beta}""").parseExpression()
        assertNotNull(mathNode)
        assertTrue(mathNode is MathNode.Fraction)
    }

    @Test
    fun normalizeRestoredSessionsPrunesAccumulatedBlankSessionsAndRestoresActiveId() {
        val blank1 = ConversationSession(
            id = "blank-1",
            title = "新对话",
            mode = "chat",
            createdAt = 1000L,
            updatedAt = 1000L
        )
        val blank2 = ConversationSession(
            id = "blank-2",
            title = "新 Work 任务",
            mode = "work",
            createdAt = 1100L,
            updatedAt = 1100L
        )
        val activeReal = ConversationSession(
            id = "real-1",
            title = "排查启动空白会话漏洞",
            mode = "chat",
            createdAt = 1200L,
            updatedAt = 2000L,
            chatMessages = listOf(ChatMessage(id = "m1", role = "user", text = "分析原因")),
            contextHistory = listOf(ContextTurn(role = "user", content = "分析原因"))
        )

        val json = CoreProtocol.serializeSessionsStore(listOf(blank1, blank2, activeReal), "blank-1")
        val (restored, activeId) = CoreProtocol.deserializeSessionsStore(json)
        assertEquals(1, restored.size)
        assertEquals("real-1", restored[0].id)
        assertEquals("real-1", activeId)

        val allBlankJson = CoreProtocol.serializeSessionsStore(listOf(blank1, blank2), "blank-2")
        val (blankRestored, blankActiveId) = CoreProtocol.deserializeSessionsStore(allBlankJson)
        assertEquals(1, blankRestored.size)
        assertEquals("blank-2", blankRestored[0].id)
        assertEquals("blank-2", blankActiveId)
    }

    @Test
    fun parseEngineTurnResultDistinguishesContextTokensFromCumulativePromptTokens() {
        val raw = """
            {
              "ok": true,
              "content": "完成多轮工具调用",
              "finish_reason": "stop",
              "rounds": 3,
              "usage": {
                "prompt_tokens": 1800,
                "context_tokens": 2050,
                "cumulative_prompt_tokens": 5200,
                "completion_tokens": 450,
                "total_tokens": 2250,
                "prompt_cache_hit_tokens": 1600,
                "prompt_cache_miss_tokens": 200,
                "server_tool_use": {
                  "tool_search": 1,
                  "tool_fetch": 2
                }
              }
            }
        """.trimIndent()
        val parsed = CoreProtocol.parseEngineTurnResult(raw)
        assertTrue(parsed.ok)
        assertEquals(1800, parsed.promptTokens)
        assertEquals(2050, parsed.contextTokens)
        assertEquals(5200, parsed.cumulativePromptTokens)
        assertEquals(450, parsed.completionTokens)
        assertEquals(3, parsed.serverToolUseCount)
        assertTrue(parsed.usageText.contains("上下文 2050 (累计输入 5200)"))
        assertTrue(parsed.usageText.contains("输出 450 tokens"))
        assertTrue(parsed.usageText.contains("缓存命中 1600 · 88%"))
        assertTrue(parsed.usageText.contains("服务端联网调用 3 次"))
    }
}
