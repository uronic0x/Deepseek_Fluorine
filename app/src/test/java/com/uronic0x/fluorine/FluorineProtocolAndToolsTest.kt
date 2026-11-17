package com.uronic0x.fluorine

import com.uronic0x.fluorine.engine.DeterministicTools
import com.uronic0x.fluorine.protocol.CoreProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class FluorineProtocolAndToolsTest {

    @Test
    fun `calc evaluates precedence and math functions accurately`() {
        assertEquals(14.0, DeterministicTools.evalExpr("2 + 3 * 4"), 1e-9)
        assertEquals(80.0, DeterministicTools.evalExpr("(2 + 3) * 4^2"), 1e-9)
        assertEquals(14.0, DeterministicTools.evalExpr("-5 + sqrt(144) + max(3, 7)"), 1e-9)
        assertEquals(13.0, DeterministicTools.evalExpr("clamp(15, 0, 10) + log2(8)"), 1e-9)
    }

    @Test
    fun `date_calc computes civil calendar add and workdays`() {
        val addRes = DeterministicTools.executeDeterministicTool(
            "date_calc",
            JSONObject("""{"op":"add","date":"2026-11-17","days":14}""")
        )!!
        assertEquals("2026-12-01", addRes.getString("result"))
        assertEquals("Tuesday", addRes.getString("weekday"))

        val wdRes = DeterministicTools.executeDeterministicTool(
            "date_calc",
            JSONObject("""{"op":"workdays","date":"2026-11-16","date2":"2026-11-23"}""")
        )!!
        assertEquals(5L, wdRes.getLong("workdays"))
    }

    @Test
    fun `unit_convert handles temperature and energy`() {
        val temp = DeterministicTools.executeDeterministicTool(
            "unit_convert",
            JSONObject("""{"value":100,"from":"C","to":"F"}""")
        )!!
        assertEquals(212.0, temp.getDouble("result"), 1e-9)

        val energy = DeterministicTools.executeDeterministicTool(
            "unit_convert",
            JSONObject("""{"value":2,"from":"kWh","to":"kJ"}""")
        )!!
        assertEquals(7200.0, energy.getDouble("result"), 1e-9)
    }

    @Test
    fun `json_query csv_summary text_diff and sha256 match rust implementation`() {
        val jq = DeterministicTools.executeDeterministicTool(
            "json_query",
            JSONObject("""{"json_str":"{\"items\":[{\"name\":\"a\",\"price\":12}]}","pointer":"/items/0/price"}""")
        )!!
        assertEquals(12, jq.getInt("value"))

        val csv = DeterministicTools.executeDeterministicTool(
            "csv_summary",
            JSONObject("""{"csv_text":"item,cost\n\"a,b\",10\nc,20"}""")
        )!!
        assertEquals(2, csv.getInt("row_count"))
        assertEquals(30.0, csv.getJSONArray("columns").getJSONObject(1).getDouble("sum"), 1e-9)

        val diff = DeterministicTools.executeDeterministicTool(
            "text_diff",
            JSONObject("""{"old_text":"a\nb\nc","new_text":"a\nx\nc\nd"}""")
        )!!
        assertEquals(2, diff.getInt("added"))
        assertEquals(1, diff.getInt("removed"))
        assertEquals(2, diff.getInt("unchanged"))

        val sha = DeterministicTools.executeDeterministicTool(
            "encode_hash",
            JSONObject("""{"op":"sha256","input":"abc"}""")
        )!!
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha.getString("result")
        )
    }

    @Test
    fun `sse accumulator merges chunks and validates finish reason`() {
        val acc = CoreProtocol.SseAccumulator()
        acc.pushFrame("""{"choices":[{"delta":{"reasoning_content":"先分析..."}}]}""")
        acc.pushFrame("""{"choices":[{"delta":{"content":"最终结论"},"finish_reason":"stop"}]}""")
        acc.pushFrame("""{"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":50}}""")
        acc.pushFrame("[DONE]")

        val res = acc.result()
        assertEquals("先分析...", res.message.reasoningContent)
        assertEquals("最终结论", res.message.content)
        assertNotNull(res.usage)
        assertEquals(100L, res.usage?.promptTokens)
        assertEquals(50L, res.usage?.completionTokens)
    }

    @Test
    fun `peak utc pricing doubles weekday peak hours`() {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.set(2026, Calendar.NOVEMBER, 17, 2, 0, 0) // Tuesday 02:00 UTC (Peak)
        assertTrue(CoreProtocol.isPeakUtc(cal.time))
    }

    @Test
    fun `session persistence round-trips messages history and work reports`() {
        val snap = com.uronic0x.fluorine.protocol.SessionSnapshot(
            convTitle = "测试持久化",
            mode = com.uronic0x.fluorine.protocol.WorkMode.WORK,
            messages = listOf(
                com.uronic0x.fluorine.protocol.UiChatMessage(
                    id = 1L,
                    role = "user",
                    content = "计算 2+2"
                ),
                com.uronic0x.fluorine.protocol.UiChatMessage(
                    id = 2L,
                    role = "assistant",
                    content = "结果是 4",
                    reasoningContent = "2+2=4",
                    usageText = "输入 10 · 输出 5 tokens"
                )
            ),
            history = listOf(
                com.uronic0x.fluorine.protocol.ProtocolMessage(role = "user", content = "计算 2+2"),
                com.uronic0x.fluorine.protocol.ProtocolMessage(role = "assistant", content = "结果是 4")
            ),
            showTaskPanel = true,
            planText = "步骤 1：计算"
        )
        val raw = CoreProtocol.serializeSession(snap)
        val restored = CoreProtocol.deserializeSession(raw)
        assertNotNull(restored)
        assertEquals("测试持久化", restored?.convTitle)
        assertEquals(com.uronic0x.fluorine.protocol.WorkMode.WORK, restored?.mode)
        assertEquals(2, restored?.messages?.size)
        assertEquals("结果是 4", restored?.messages?.get(1)?.content)
        assertEquals("步骤 1：计算", restored?.planText)
    }
}

