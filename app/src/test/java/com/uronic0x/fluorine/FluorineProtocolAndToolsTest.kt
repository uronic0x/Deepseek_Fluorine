package com.uronic0x.fluorine

import com.uronic0x.fluorine.engine.AndroidHarnessTools
import com.uronic0x.fluorine.engine.DeterministicTools
import com.uronic0x.fluorine.engine.KotlinNativeEngine
import com.uronic0x.fluorine.protocol.ChatMessage
import com.uronic0x.fluorine.protocol.ContextTurn
import com.uronic0x.fluorine.protocol.ConversationSession
import com.uronic0x.fluorine.protocol.CoreProtocol
import com.uronic0x.fluorine.protocol.LocalToolCallRecord
import com.uronic0x.fluorine.ui.components.LatexMathParser
import com.uronic0x.fluorine.ui.components.MathNode
import com.uronic0x.fluorine.ui.components.MdBlock
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
    fun proOmitsWebSearchWhileFlashIncludesItByDefault() {
        val proReq = JSONObject()
            .put("model", "deepseek-v4-pro")
            .put("stage", "chat")
            .put("use_web", true)
            .put("use_thinking", true)
            .put("effort", "high")
            .put("max_tokens", 8192)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", "Hello")
                        .put("reasoning_content", "must be stripped")
                )
            )

        val proPayload = KotlinNativeEngine.buildUpstreamPayload(proReq)
        assertFalse(proPayload.has("tools"))
        assertEquals("enabled", proPayload.getJSONObject("thinking").getString("type"))
        assertEquals("high", proPayload.getJSONObject("reasoning").getString("effort"))
        assertFalse(
            proPayload.getJSONArray("messages")
                .getJSONObject(1)
                .has("reasoning_content")
        )

        val flashReq = JSONObject(proReq.toString()).put("model", "deepseek-flash")
        val flashPayload = KotlinNativeEngine.buildUpstreamPayload(flashReq)
        assertTrue(flashPayload.has("tools"))
        assertEquals(
            "web_search",
            flashPayload.getJSONArray("tools").getJSONObject(0).getString("type")
        )
    }

    @Test
    fun autonomousWorkModePreservesToolHistoryWithoutReasoningForPrefixCacheHit() {
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
            .put("effort", "medium")
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "user").put("content", "Compute 2^10"))
                    .put(
                        JSONObject()
                            .put("role", "assistant")
                            .put("content", "")
                            .put("reasoning_content", "strip historical reasoning")
                            .put("tool_calls", toolCallArr)
                    )
                    .put(
                        JSONObject()
                            .put("role", "tool")
                            .put("tool_call_id", "call_1")
                            .put("content", """{"ok":true,"result":1024}""")
                    )
                    .put(JSONObject().put("role", "assistant").put("content", "2^10 = 1024"))
                    .put(JSONObject().put("role", "user").put("content", "Now add 24"))
            )

        val payload = KotlinNativeEngine.buildUpstreamPayload(workReq)
        val tools = payload.getJSONArray("tools")
        // 1 web_search + 9 deterministic + 9 harness = 19 tools
        assertEquals(19, tools.length())

        val msgs = payload.getJSONArray("messages")
        // system + user1 + assistant(tool_calls) + tool + assistant1 + user2 = 6 messages
        assertEquals(6, msgs.length())
        assertEquals("system", msgs.getJSONObject(0).getString("role"))
        assertTrue(msgs.getJSONObject(2).has("tool_calls"))
        assertFalse(msgs.getJSONObject(2).has("reasoning_content"))
        assertEquals("tool", msgs.getJSONObject(3).getString("role"))
        assertEquals("call_1", msgs.getJSONObject(3).getString("tool_call_id"))
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
        } finally {
            tempWs.deleteRecursively()
        }
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

        val session1 = ConversationSession(
            id = "s1",
            title = "公式推导",
            mode = "chat",
            createdAt = 1000L,
            updatedAt = 2000L,
            chatMessages = listOf(ChatMessage(id = "m1", role = "user", text = "推导高斯积分")),
            contextHistory = listOf(ContextTurn("user", "推导高斯积分"))
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
                    localToolCalls = toolCalls
                )
            ),
            contextHistory = listOf(
                ContextTurn(
                    role = "assistant",
                    content = "已生成报告",
                    subturnMessages = listOf("""{"role":"tool","tool_call_id":"c1","content":"{}"}""")
                )
            )
        )

        val json = CoreProtocol.serializeSessionsStore(listOf(session1, session2), "s2")
        val (restored, activeId) = CoreProtocol.deserializeSessionsStore(json)
        assertEquals("s2", activeId)
        assertEquals(2, restored.size)
        assertEquals("生成报告", restored[1].title)
        assertEquals(2, restored[1].chatMessages[0].localToolCalls.size)
        assertEquals(1, restored[1].contextHistory[0].subturnMessages.size)
    }

    @Test
    fun markdownAndLatexMathParsersHandleBlocksAndFormulas() {
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
}
