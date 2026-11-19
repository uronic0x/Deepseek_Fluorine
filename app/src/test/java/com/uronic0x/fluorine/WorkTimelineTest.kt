package com.uronic0x.fluorine

import com.uronic0x.fluorine.bridge.FluorineBridge
import com.uronic0x.fluorine.protocol.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class WorkTimelineTest {
    @Test
    fun commentaryAndToolsSurviveFinalReconciliationAndSessionReload() {
        val timeline = WorkTimeline()
        val call = LocalToolCallRecord(1, "c1", "calc", "{}", "20")
        timeline.accept(StreamUiEvent.RoundStarted(1))
        timeline.accept(StreamUiEvent.ContentDelta("先计算。"))
        val beforeTool = timeline.steps
        timeline.accept(StreamUiEvent.ToolRecord(call))
        timeline.accept(StreamUiEvent.RoundStarted(2))
        timeline.accept(StreamUiEvent.ThinkingDelta("验证结果"))
        timeline.accept(StreamUiEvent.ContentDelta("结果为 20。"))
        val citation = CitationPoint(2, listOf(1))
        timeline.setCitations(listOf(citation))
        assertTrue(beforeTool[0].localToolCalls.isEmpty())

        val result = CoreProtocol.parseEngineTurnResult(JSONObject()
            .put("ok", true).put("content", "结果为 20。")
            .put("work_steps", CoreProtocol.serializeWorkSteps(timeline.steps)).toString())
        val message = ChatMessage("m1", "assistant", result.content, workSteps = result.workSteps)
        val session = ConversationSession("s1", "Work", chatMessages = listOf(message))
        val (sessions, _) = CoreProtocol.deserializeSessionsStore(
            CoreProtocol.serializeSessionsStore(listOf(session), "s1"))
        val restored = sessions.single().chatMessages.single()
        assertEquals("结果为 20。", restored.text)
        assertEquals(listOf("先计算。", "结果为 20。"), restored.workSteps.map { it.content })
        assertEquals(call, restored.workSteps[0].localToolCalls.single())
        assertEquals(citation, restored.workSteps[1].citations.single())
    }

    @Test
    fun legacyResultsHaveNoInventedTimeline() {
        val result = CoreProtocol.parseEngineTurnResult("""{"ok":true,"content":"旧回答"}""")
        assertEquals("旧回答", result.content)
        assertTrue(result.workSteps.isEmpty())
    }

    @Test
    fun largerContextKeepsWholeUserAssistantPairsAndToolSubturns() {
        val history = (0 until 600).flatMap { i -> listOf(
            ContextTurn("user", "question $i"),
            ContextTurn("assistant", "answer $i", subturnMessages = listOf("tool trace $i"))
        ) }
        val expanded = CoreProtocol.recentContext(history, 1000)
        assertEquals(1000, expanded.size)
        assertEquals("question 100", expanded.first().content)
        assertEquals(listOf("tool trace 599"), expanded.last().subturnMessages)
        val odd = CoreProtocol.recentContext(history, 101)
        assertEquals("user", odd.first().role)
        assertEquals(100, odd.size)
    }

    @Test
    fun rustWorkLoopEventsPreserveCommentaryToolsAndSubturnsWithoutDuplication() {
        assertRustWorkStream(cancelAfterTool = false)
    }

    @Test
    fun cancellationAfterToolPreservesCommentaryAndOutputInRustBridge() {
        assertRustWorkStream(cancelAfterTool = true)
    }

    private fun assertRustWorkStream(cancelAfterTool: Boolean) {
        val cancelledInNative = AtomicBoolean(false)
        val step1 = JSONObject()
            .put("round", 1)
            .put("thinking", "")
            .put("content", "先计算。")
            .put(
                "local_tool_calls",
                JSONArray().put(
                    JSONObject()
                        .put("round", 1)
                        .put("id", "c1")
                        .put("name", "calc")
                        .put("arguments", """{"expression":"(2+3)*4"}""")
                        .put("result", """{"ok":true,"value":20}""")
                )
            )
        val step2 = JSONObject()
            .put("round", 2)
            .put("thinking", "核对结果")
            .put("content", "结果为 20。")
            .put("local_tool_calls", JSONArray())

        val toolCallsArr = JSONArray().put(
            JSONObject()
                .put("round", 1)
                .put("id", "c1")
                .put("name", "calc")
                .put("arguments", """{"expression":"(2+3)*4"}""")
                .put("result", """{"ok":true,"value":20}""")
        )
        val subturnsArr = JSONArray()
            .put(JSONObject().put("role", "assistant").put("content", "先计算。"))
            .put(JSONObject().put("role", "tool").put("tool_call_id", "c1").put("content", """{"ok":true,"value":20}"""))

        val fakeBackend = object : FluorineBridge.NativeBackend {
            override val isLoaded: Boolean = true
            override fun getMeta(workspacePath: String, allowSdcardReadonly: Boolean): String = """{"ok":true}"""
            override fun fetchModels(apiKey: String, baseUrl: String, connectTimeoutSec: Int, readTimeoutSec: Int): String = """{"ok":true}"""
            override fun uploadFile(
                apiKey: String, baseUrl: String, filePath: String, filename: String,
                mimeType: String, purpose: String, allowedRootsNewline: String,
                connectTimeoutSec: Int, readTimeoutSec: Int
            ): String = """{"ok":true}"""
            override fun executeLocalTool(name: String, argumentsJson: String, workspacePath: String, allowSdcardReadonly: Boolean): String = """{"ok":true}"""
            override fun buildUpstreamPayload(requestJson: String): String = """{"ok":true}"""
            override fun cancelStream(requestId: Long): Boolean {
                cancelledInNative.set(true)
                return true
            }

            override fun streamChat(
                requestId: Long,
                apiKey: String,
                requestJson: String,
                workspacePath: String,
                allowSdcardReadonly: Boolean,
                callback: FluorineBridge.JniStreamCallback
            ) {
                if (!callback.onEvent("""{"local_tool_event":{"type":"round_started","round":1}}""")) return
                val r1Delta = JSONObject().put(
                    "choices",
                    JSONArray().put(
                        JSONObject().put(
                            "delta",
                            JSONObject()
                                .put("work_steps", JSONArray().put(step1))
                                .put("local_tool_calls", toolCallsArr)
                                .put("subturn_messages", subturnsArr)
                        )
                    )
                )
                if (!callback.onEvent(r1Delta.toString())) return

                if (cancelledInNative.get()) {
                    callback.onEvent(
                        JSONObject()
                            .put("work_steps", JSONArray().put(step1))
                            .put("subturn_messages", subturnsArr)
                            .put("error", JSONObject().put("code", "cancelled").put("message", "Request cancelled"))
                            .toString()
                    )
                    return
                }

                if (!callback.onEvent("""{"local_tool_event":{"type":"round_started","round":2}}""")) return
                val r2Delta = JSONObject().put(
                    "choices",
                    JSONArray().put(
                        JSONObject()
                            .put(
                                "delta",
                                JSONObject()
                                    .put("content", "结果为 20。")
                                    .put("reasoning_content", "核对结果")
                                    .put("work_steps", JSONArray().put(step1).put(step2))
                                    .put("local_tool_calls", toolCallsArr)
                                    .put("subturn_messages", subturnsArr)
                            )
                            .put("finish_reason", "stop")
                    )
                )
                if (!callback.onEvent(r2Delta.toString())) return
                callback.onEvent("[DONE]")
            }
        }

        try {
            FluorineBridge.setBackendForTesting(fakeBackend)
            val live = WorkTimeline()
            val result = CoreProtocol.parseEngineTurnResult(
                FluorineBridge.chatCompleteStreamingJson(
                    requestId = 9001L,
                    requestJson = JSONObject()
                        .put("api_key", "test-only")
                        .put("stage", "work")
                        .put("max_rounds", 2)
                        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "计算")))
                        .toString(),
                    onEvent = {
                        live.accept(it)
                        if (cancelAfterTool && it is StreamUiEvent.ToolRecord) {
                            FluorineBridge.cancelStream(9001L)
                        }
                    }
                )
            )

            if (cancelAfterTool) {
                assertFalse(result.ok)
                assertTrue(cancelledInNative.get())
                assertEquals("先计算。", result.workSteps.single().content)
                assertEquals("calc", result.workSteps.single().localToolCalls.single().name)
                return
            }

            assertTrue(result.error, result.ok)
            assertEquals(listOf("先计算。", "结果为 20。"), result.workSteps.map { it.content })
            assertEquals(result.workSteps, live.steps)
            assertEquals("calc", result.workSteps[0].localToolCalls.single().name)
            assertEquals("结果为 20。", result.content)
            assertEquals(2, result.subturnMessages.size)
        } finally {
            FluorineBridge.setBackendForTesting(null)
        }
    }
}
