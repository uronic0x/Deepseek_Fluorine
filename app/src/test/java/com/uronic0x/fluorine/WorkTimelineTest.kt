package com.uronic0x.fluorine

import com.sun.net.httpserver.HttpServer
import com.uronic0x.fluorine.engine.KotlinNativeEngine
import com.uronic0x.fluorine.protocol.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.file.Files

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
    fun oneToolBatchStillGetsAFinalResponseAndOrderedStreamEvents() = assertToolLoop(streaming = true)

    @Test
    fun jsonFallbackAlsoPreservesCommentaryAndTools() = assertToolLoop(streaming = false)

    @Test
    fun cancellationAfterAToolPreservesItsCommentaryAndOutput() = assertToolLoop(streaming = true, cancel = true)

    private fun assertToolLoop(streaming: Boolean, cancel: Boolean = false) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = mutableListOf<JSONObject>()
        server.createContext("/chat/completions") { exchange ->
            val request = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
            requests.add(request)
            val first = requests.size == 1
            val delta = JSONObject().put("content", if (first) "先计算。" else "结果为 20。")
            if (first) delta.put("tool_calls", JSONArray().put(JSONObject()
                .put("index", 0).put("id", "c1").put("type", "function")
                .put("function", JSONObject().put("name", "calc")
                    .put("arguments", """{"expression":"(2+3)*4"}"""))))
            val choice = JSONObject().put(if (streaming) "delta" else "message", delta)
                .put("finish_reason", if (first) "tool_calls" else "stop")
            val response = JSONObject().put("choices", JSONArray().put(choice)).toString()
            val body = if (streaming) "data: $response\n\ndata: [DONE]\n\n" else response
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", if (streaming) "text/event-stream" else "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val workspace = Files.createTempDirectory("work-timeline-test").toFile()
        try {
            val live = WorkTimeline()
            val result = CoreProtocol.parseEngineTurnResult(KotlinNativeEngine.chatCompleteStreaming(
                requestId = 9001L,
                requestJson = JSONObject().put("api_key", "test-only")
                    .put("base_url", "http://127.0.0.1:${server.address.port}")
                    .put("stage", "work").put("max_rounds", 1)
                    .put("messages", JSONArray().put(JSONObject()
                        .put("role", "user").put("content", "计算"))).toString(),
                defaultWorkspaceDir = workspace,
                onEvent = {
                    live.accept(it)
                    if (cancel && it is StreamUiEvent.ToolRecord) KotlinNativeEngine.cancelStream(9001L)
                }
            ))
            if (cancel) {
                assertFalse(result.ok)
                assertEquals(1, requests.size)
                assertEquals("先计算。", result.workSteps.single().content)
                assertEquals("calc", result.workSteps.single().localToolCalls.single().name)
                return
            }
            assertTrue(result.error, result.ok)
            assertEquals(2, requests.size)
            assertEquals("none", requests[1].getString("tool_choice"))
            assertEquals(listOf("先计算。", "结果为 20。"), result.workSteps.map { it.content })
            assertEquals(result.workSteps, live.steps)
            assertEquals("calc", result.workSteps[0].localToolCalls.single().name)
            assertEquals("结果为 20。", result.content)
        } finally {
            server.stop(0)
            workspace.deleteRecursively()
        }
    }
}
