package com.cone.agent.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeProtocolTest {

    @Test fun fencedJsonActionIsParsed() {
        val reply = """
            我先看一下现有文件。
            ```json
            {"action": "read_file", "path": "index.html"}
            ```
            读完再改。
        """.trimIndent()
        val segments = CodeProtocol.parse(reply)
        assertEquals(3, segments.size)
        assertEquals("我先看一下现有文件。", (segments[0] as CodeSegment.Prose).text)
        val call = (segments[1] as CodeSegment.Call).call
        assertEquals("read_file", call.tool)
        assertEquals("index.html", call.path)
        assertEquals("读完再改。", (segments[2] as CodeSegment.Prose).text)
    }

    @Test fun parallelActionsKeepOrder() {
        val reply = """
            ```json
            {"action": "read_file", "path": "a.js"}
            ```
            ```json
            {"action": "list_dir", "path": "src"}
            ```
            ```json
            {"action": "search", "query": "TODO"}
            ```
        """.trimIndent()
        assertEquals(listOf("read_file", "list_dir", "search"), CodeProtocol.calls(reply).map { it.tool })
    }

    @Test fun bareJsonObjectIsPromoted() {
        val reply = "先读文件：{\"action\": \"read_file\", \"path\": \"app.js\"} 然后继续。"
        val calls = CodeProtocol.calls(reply)
        assertEquals(1, calls.size)
        assertEquals("app.js", calls[0].path)
    }

    @Test fun xmlStyleToolCallIsNormalized() {
        val reply = "读取配置 <read_file path=\"config.json\"/> 再继续。"
        val calls = CodeProtocol.calls(reply)
        assertEquals(1, calls.size)
        assertEquals("read_file", calls[0].tool)
        assertEquals("config.json", calls[0].path)
    }

    @Test fun streamingHidesIncompleteFence() {
        val partial = "我先建个文件。\n```json\n{\"action\": \"create_file\", \"path\": \"a.js\", \"content\": \"let"
        val segments = CodeProtocol.parse(partial, streaming = true)
        assertEquals(1, segments.size)
        assertEquals("我先建个文件。", (segments[0] as CodeSegment.Prose).text)
        assertTrue(CodeProtocol.calls(partial).isEmpty())
    }

    @Test fun editFileCarriesOldAndNew() {
        val reply = """
            ```json
            {"action": "edit_file", "path": "app.js", "old": "let a = 1", "new": "let a = 2"}
            ```
        """.trimIndent()
        val call = CodeProtocol.calls(reply).single()
        assertEquals("edit_file", call.tool)
        assertEquals("let a = 1", call.old)
        assertEquals("let a = 2", call.new)
        assertEquals(listOf("let a = 1" to "let a = 2"), call.edits)
        assertEquals("", call.content)
    }

    @Test fun updateTodosBecomesMarkerText() {
        val reply = """
            ```json
            {"action": "update_todos", "todos": [
              {"content": "读现有代码", "status": "completed"},
              {"content": "写页面", "status": "in_progress"},
              {"content": "自测", "status": "pending"}
            ]}
            ```
        """.trimIndent()
        val call = CodeProtocol.calls(reply).single()
        assertEquals("update_todos", call.tool)
        assertEquals(3, call.todos.size)
        assertEquals("[x] 读现有代码", call.content.lines()[0])
        assertEquals("[>] 写页面", call.content.lines()[1])
        assertEquals("[ ] 自测", call.content.lines()[2])
    }

    @Test fun legacyTagsStillRender() {
        val reply = "看一下 <read path=\"a.js\"/> 然后 <write path=\"b.js\">console.log(1)</write>"
        val calls = CodeProtocol.calls(reply)
        assertEquals(listOf("read", "write"), calls.map { it.tool })
        assertEquals("b.js", calls[1].path)
        assertEquals("console.log(1)", calls[1].content)
    }

    @Test fun aliasesMapToCanonicalNames() {
        val reply = """
            ```json
            {"action": "write", "path": "x.js", "content": "1"}
            ```
            ```json
            {"action": "todo", "content": "[ ] a"}
            ```
        """.trimIndent()
        assertEquals(listOf("create_file", "update_todos"), CodeProtocol.calls(reply).map { it.tool })
    }

    @Test fun plainProseHasNoCalls() {
        val segments = CodeProtocol.parse("做完了。页面能直接打开。")
        assertEquals(1, segments.size)
        assertTrue(segments[0] is CodeSegment.Prose)
        assertTrue(CodeProtocol.calls("做完了。").isEmpty())
    }

    @Test fun trailingCommaIsRepaired() {
        val reply = """
            ```json
            {"action": "create_file", "path": "a.txt", "content": "hi",}
            ```
        """.trimIndent()
        val call = CodeProtocol.calls(reply).single()
        assertEquals("create_file", call.tool)
        assertEquals("hi", call.content)
    }

    @Test fun escapedNewlinesInContentAreDecoded() {
        val reply = """
            ```json
            {"action": "create_file", "path": "a.txt", "content": "line1\nline2"}
            ```
        """.trimIndent()
        val call = CodeProtocol.calls(reply).single()
        assertEquals("line1\nline2", call.content)
    }

    @Test fun fenceWithoutNewlineStillParses() {
        val reply = """```json{"action": "delete", "path": "tmp.js"}```"""
        val call = CodeProtocol.calls(reply).single()
        assertEquals("delete", call.tool)
        assertEquals("tmp.js", call.path)
    }
}
