package com.uronic0x.fluorine

import com.uronic0x.fluorine.engine.AndroidHarnessTools
import com.uronic0x.fluorine.engine.DeterministicTools
import com.uronic0x.fluorine.engine.KotlinNativeEngine
import com.uronic0x.fluorine.protocol.ChatAttachment
import com.uronic0x.fluorine.protocol.ChatMessage
import com.uronic0x.fluorine.protocol.CitationPoint
import com.uronic0x.fluorine.protocol.ContextTurn
import com.uronic0x.fluorine.protocol.ConversationSession
import com.uronic0x.fluorine.protocol.CoreProtocol
import com.uronic0x.fluorine.protocol.LocalToolCallRecord
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

class FluorineProtocolAndToolsTest {

    @Test
    fun proOmitsWebSearchAndUsesTopLevelReasoningEffort() {
        val proReq = JSONObject()
            .put("model", "deepseek-v4-pro")
            .put("stage", "chat")
            .put("use_web", true)
            .put("use_thinking", true)
            .put("effort", "medium")
            .put("max_tokens", 8192)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", "Hello")
                        .put("reasoning_content", "must be stripped on user")
                )
            )

        val proPayload = KotlinNativeEngine.buildUpstreamPayload(proReq)
        assertFalse(proPayload.has("tools"))
        assertFalse(proPayload.has("reasoning"))
        assertEquals("enabled", proPayload.getJSONObject("thinking").getString("type"))
        assertEquals("high", proPayload.getString("reasoning_effort"))
        assertFalse(
            proPayload.getJSONArray("messages")
                .getJSONObject(1)
                .has("reasoning_content")
        )

        val flashReq = JSONObject(proReq.toString()).put("model", "deepseek-flash")
        val flashPayload = KotlinNativeEngine.buildUpstreamPayload(flashReq)
        assertTrue(flashPayload.has("tools"))
        val tools = flashPayload.getJSONArray("tools")
        assertEquals(2, tools.length())
        assertEquals("deepseek_web_search", tools.getJSONObject(0).getString("type"))
        assertEquals("deepseek_read_url", tools.getJSONObject(1).getString("type"))
    }

    @Test
    fun workModeNeverMixesBuiltInSearchWithFunctionToolsAndRetainsReasoningWhenToolsPresent() {
        val toolCallArr = JSONArray().put(
            JSONObject()
                .put("id", "call_1")
                .put("type", "function")
                .put(
                    "function",
                    JSONObject()
                        .put("name", "calc_eval")
                        .put("arguments", """{"expr":"2^10"}""")
                )
        )
        val workReq = JSONObject()
            .put("model", "deepseek-flash")
            .put("stage", "work")
            .put("use_web", true)
            .put("use_thinking", true)
            .put("use_local_tools", true)
            .put("effort", "max")
            .put(
                "tool_config",
                WorkToolsConfig(
                    deterministic = true,
                    workspaceRead = true,
                    workspaceWrite = true,
                    git = true,
                    shellExec = true,
                    deviceControl = true
                ).toJson()
            )
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "user").put("content", "Compute 2^10"))
                    .put(
                        JSONObject()
                            .put("role", "assistant")
                            .put("content", "")
                            .put("reasoning_content", "step-by-step reasoning")
                            .put("tool_calls", toolCallArr)
                    )
                    .put(
                        JSONObject()
                            .put("role", "tool")
                            .put("tool_call_id", "call_1")
                            .put("content", """{"ok":true,"result":1024}""")
                    )
                    .put(
                        JSONObject()
                            .put("role", "assistant")
                            .put("content", "2^10 = 1024")
                            .put("reasoning_content", "final turn reasoning")
                    )
                    .put(JSONObject().put("role", "user").put("content", "Now add 24"))
            )

        val payload = KotlinNativeEngine.buildUpstreamPayload(workReq)
        val tools = payload.getJSONArray("tools")
        // 2 function web tools (web_search + web_fetch) + 9 deterministic + 9 harness = 20
        assertEquals(20, tools.length())
        for (i in 0 until tools.length()) {
            val type = tools.getJSONObject(i).getString("type")
            assertEquals("function", type)
            assertFalse(type == "deepseek_web_search" || type == "deepseek_read_url")
        }

        val msgs = payload.getJSONArray("messages")
        assertEquals(6, msgs.length())
        assertEquals("system", msgs.getJSONObject(0).getString("role"))
        assertTrue(msgs.getJSONObject(2).has("tool_calls"))
        assertEquals("step-by-step reasoning", msgs.getJSONObject(2).getString("reasoning_content"))
        assertEquals("final turn reasoning", msgs.getJSONObject(4).getString("reasoning_content"))
    }

    @Test
    fun workToolsConfigCustomizesDeclarationsAndEnforcesExecutionAllowlist() {
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
        assertFalse("git_cmd" in enabled)
        assertFalse("termux_api" in enabled)
        assertFalse("self_adb" in enabled)

        val tempWs: File = Files.createTempDirectory("fluorine_allowlist_test").toFile()
        try {
            val blockedWrite = KotlinNativeEngine.executeLocalTool(
                name = "fs_write",
                rawArguments = """{"path":"x.txt","content":"nope"}""",
                workspaceRoot = tempWs,
                allowSdcardReadonly = false,
                enabledTools = enabled
            )
            assertFalse(blockedWrite.getBoolean("ok"))

            val blockedStats = KotlinNativeEngine.executeLocalTool(
                name = "stats_summary",
                rawArguments = """{"values":[1,2,3]}""",
                workspaceRoot = tempWs,
                allowSdcardReadonly = false,
                enabledTools = enabled
            )
            assertFalse(blockedStats.getBoolean("ok"))

            val allowedShell = KotlinNativeEngine.executeLocalTool(
                name = "shell_exec",
                rawArguments = """{"command":"echo ok_shell"}""",
                workspaceRoot = tempWs,
                allowSdcardReadonly = false,
                enabledTools = enabled
            )
            assertTrue(allowedShell.getBoolean("ok"))
            assertTrue(allowedShell.getString("output").contains("ok_shell"))

            // When device_control is enabled, termux_api runs and truthfully reports ok=false if binary is missing
            val termuxUnavailable = AndroidHarnessTools.execute(
                name = "termux_api",
                args = JSONObject().put("api", "battery-status"),
                workspaceRoot = tempWs,
                allowSdcardReadonly = false,
                enabledTools = setOf("termux_api")
            )
            assertFalse(termuxUnavailable.getBoolean("ok"))
        } finally {
            tempWs.deleteRecursively()
        }
    }

    @Test
    fun deterministicToolsComputeAccurateResults() {
        val calcRes = DeterministicTools.execute(
            "calc_eval",
            JSONObject()
                .put("expr", "round(sqrt(2)^2 + x, 4)")
                .put("vars", JSONObject().put("x", 3.5))
        )
        assertTrue(calcRes.getBoolean("ok"))
        assertEquals(5.5, calcRes.getDouble("result"), 1e-6)

        val statsRes = DeterministicTools.execute(
            "stats_summary",
            JSONObject().put("values", JSONArray(listOf(10, 20, 30, 40, 50)))
        )
        assertTrue(statsRes.getBoolean("ok"))
        assertEquals(30.0, statsRes.getDouble("mean"), 1e-6)
        assertEquals(30.0, statsRes.getDouble("median"), 1e-6)

        val unitRes = DeterministicTools.execute(
            "unit_convert",
            JSONObject().put("value", 100.0).put("from", "c").put("to", "f")
        )
        assertTrue(unitRes.getBoolean("ok"))
        assertEquals(212.0, unitRes.getDouble("result"), 1e-6)

        val dateRes = DeterministicTools.execute(
            "date_calc",
            JSONObject().put("op", "diff_days").put("date", "2026-01-01").put("other_date", "2026-01-31")
        )
        assertTrue(dateRes.getBoolean("ok"))
        assertEquals(30L, dateRes.getLong("days"))
    }

    @Test
    fun workspaceHarnessToolsWriteReadEditAndBlockTraversal() {
        val tempWs: File = Files.createTempDirectory("fluorine_ws_test").toFile()
        try {
            val writeRes = AndroidHarnessTools.execute(
                "fs_write",
                JSONObject().put("path", "notes/hello.txt").put("content", "line1\nline2\nline3"),
                tempWs,
                allowSdcardReadonly = false
            )
            assertTrue(writeRes.getBoolean("ok"))
            assertEquals("notes/hello.txt", writeRes.getString("path"))

            val editRes = AndroidHarnessTools.execute(
                "fs_edit",
                JSONObject()
                    .put("path", "notes/hello.txt")
                    .put("old_text", "line2")
                    .put("new_text", "line2_updated"),
                tempWs,
                allowSdcardReadonly = false
            )
            assertTrue(editRes.getBoolean("ok"))

            val readRes = AndroidHarnessTools.execute(
                "fs_read",
                JSONObject().put("path", "notes/hello.txt").put("start_line", 2).put("end_line", 2),
                tempWs,
                allowSdcardReadonly = false
            )
            assertTrue(readRes.getBoolean("ok"))
            assertEquals("line2_updated", readRes.getString("content"))

            val traversalRes = AndroidHarnessTools.execute(
                "fs_write",
                JSONObject().put("path", "../escape.txt").put("content", "bad"),
                tempWs,
                allowSdcardReadonly = false
            )
            assertFalse(traversalRes.getBoolean("ok"))

            // Append limit check (512 KiB max)
            val hugeChunk = "a".repeat(300 * 1024)
            assertTrue(
                AndroidHarnessTools.execute(
                    "fs_write",
                    JSONObject().put("path", "big.txt").put("content", hugeChunk),
                    tempWs,
                    allowSdcardReadonly = false
                ).getBoolean("ok")
            )
            val appendOverflow = AndroidHarnessTools.execute(
                "fs_write",
                JSONObject().put("path", "big.txt").put("content", hugeChunk).put("append", true),
                tempWs,
                allowSdcardReadonly = false
            )
            assertFalse(appendOverflow.getBoolean("ok"))
        } finally {
            tempWs.deleteRecursively()
        }
    }

    @Test
    fun parseEngineTurnResultPreservesPartialOutputOnContentFilterAndAbort() {
        val raw = JSONObject()
            .put("ok", false)
            .put("error", "Upstream terminated with content_filter")
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
        assertEquals("content_filter", parsed.finishReason)
        assertEquals("已输出思考过程", parsed.thinking)
        assertEquals("已流出的半截回答内容", parsed.content)
        assertTrue(parsed.incompleteNotice.contains("内容安全审核"))
        assertTrue(parsed.usageText.contains("输入 10"))
    }

    @Test
    fun multimodalCapabilitiesAndAttachmentPayloadBlocksWork() {
        assertTrue(CoreProtocol.supportsMultimodal("deepseek-flash", CoreProtocol.DEFAULT_MODEL_CAPABILITIES))
        assertFalse(CoreProtocol.supportsMultimodal("deepseek-v4-pro", CoreProtocol.DEFAULT_MODEL_CAPABILITIES))

        val req = JSONObject()
            .put("model", "deepseek-flash")
            .put("stage", "chat")
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", "请总结附件文档")
                        .put(
                            "attachments",
                            JSONArray().put(
                                JSONObject()
                                    .put("kind", "document")
                                    .put("name", "spec.pdf")
                                    .put("mime_type", "application/pdf")
                                    .put("file_id", "file-abc-123")
                            )
                        )
                )
            )

        val payload = KotlinNativeEngine.buildUpstreamPayload(req)
        val userMsg = payload.getJSONArray("messages").getJSONObject(1)
        val blocks = userMsg.getJSONArray("content")
        assertEquals(2, blocks.length())
        assertEquals("file", blocks.getJSONObject(0).getString("type"))
        assertEquals("file-abc-123", blocks.getJSONObject(0).getString("file_id"))
        assertEquals("text", blocks.getJSONObject(1).getString("type"))
        assertEquals("请总结附件文档", blocks.getJSONObject(1).getString("text"))
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
                name = "calc_eval",
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

        // When only blank sessions exist, keep a single blank session instead of accumulating
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
                "prompt_cache_miss_tokens": 200
              }
            }
        """.trimIndent()
        val parsed = CoreProtocol.parseEngineTurnResult(raw)
        assertTrue(parsed.ok)
        assertEquals(1800, parsed.promptTokens)
        assertEquals(2050, parsed.contextTokens)
        assertEquals(5200, parsed.cumulativePromptTokens)
        assertEquals(450, parsed.completionTokens)
        assertTrue(parsed.usageText.contains("上下文 2050 (累计输入 5200)"))
        assertTrue(parsed.usageText.contains("输出 450 tokens"))
        assertTrue(parsed.usageText.contains("缓存命中 1600 · 88%"))
    }
}

