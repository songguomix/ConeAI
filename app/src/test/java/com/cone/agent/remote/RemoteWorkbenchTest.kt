package com.cone.agent.remote

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class RemoteWorkbenchTest {
    @Test fun foldedOuterDisplayRetainsPadNavigation() {
        assertEquals(RemoteLayout.PAD, remoteLayoutFor(320, 320, true))
        assertEquals(RemoteLayout.PAD, remoteLayoutFor(392, 392, true))
        assertEquals(RemoteLayout.PAD, remoteLayoutFor(760, 760, true))
    }

    @Test fun narrowPhonesUseCompactSidebarWhilePadsKeepExpandedSidebar() {
        assertEquals(RemoteLayout.PHONE, remoteLayoutFor(360, 360, false))
        assertEquals(RemoteLayout.PHONE, remoteLayoutFor(839, 390, false))
        assertEquals(RemoteLayout.PAD, remoteLayoutFor(840, 390, false))
        assertEquals(RemoteLayout.PAD, remoteLayoutFor(600, 600, false))
        assertEquals(RemoteLayout.PAD, remoteLayoutFor(420, 600, false))
    }

    @Test fun oldDesktopSnapshotsStillDecodeWithoutNewFields() {
        val old = RemoteJson.decodeFromString<RemoteSnapshot>("""{"messages":[],"conversations":[],"unknownFutureField":42}""")
        assertTrue(old.capabilities.isEmpty())
        assertEquals("standard", old.agentMode)
        assertFalse(old.planMode)
        assertTrue(old.queuedMessages.isEmpty())
    }

    @Test fun workModesQueuesAndBooleanCommandsMatchDesktopProtocol() {
        val snapshot = RemoteJson.decodeFromString<RemoteSnapshot>("""{"capabilities":["desktop","queue"],"agentMode":"orchestrate","planMode":true,"queuedMessages":[{"id":"q1","content":"next task","modelId":"m1"}]}""")
        assertEquals("orchestrate", snapshot.agentMode)
        assertEquals("next task", snapshot.queuedMessages.single().content)
        assertTrue(RemoteCommand.SetPlanMode(true).toJsonObject()["value"]!!.jsonPrimitive.booleanOrNull!!)
        assertFalse(RemoteCommand.SetReviewMode(false).toJsonObject()["value"]!!.jsonPrimitive.booleanOrNull!!)
        assertEquals("next", RemoteCommand.EnqueueMessage("next").toJsonObject()["text"]!!.jsonPrimitive.content)
        assertEquals("q1", RemoteCommand.DequeueMessage("q1").toJsonObject()["id"]!!.jsonPrimitive.content)
    }

    @Test fun proseBlocksRecognizeHeadingsQuotesAndMergedParagraphs() {
        val blocks = groupProseBlocks("# 标题\n正文一行\n正文二行\n> 引用\n### 三级")
        assertEquals(ProseBlock.Heading(1, "标题"), blocks[0])
        assertEquals(ProseBlock.Paragraph("正文一行\n正文二行"), blocks[1])
        assertEquals(ProseBlock.Quote("引用"), blocks[2])
        assertEquals(ProseBlock.Heading(3, "三级"), blocks[3])
        assertEquals(4, blocks.size)
    }

    @Test fun proseBlocksLeaveNonHeadingHashesAsText() {
        val blocks = groupProseBlocks("#hashtag 不是标题\n####### 七级不是标题")
        assertEquals(1, blocks.size)
        val paragraph = blocks.single() as ProseBlock.Paragraph
        assertTrue(paragraph.text.contains("#hashtag"))
        assertTrue(paragraph.text.contains("#######"))
    }

    @Test fun pendingQuestionRetainsConversationScopeAndReplyPermission() {
        val snapshot = RemoteJson.decodeFromString<RemoteSnapshot>("""{"question":{"id":"q1","conversationId":"c1","content":"Pick one","options":["A","B"],"canReply":true},"messages":[{"id":"q1","role":"assistant","content":"Pick one","isQuestion":true,"questionOptions":["A","B"]}]}""")
        assertEquals("c1", snapshot.question!!.conversationId)
        assertTrue(snapshot.question!!.canReply)
        assertEquals(listOf("A", "B"), snapshot.question!!.options)
        assertTrue(snapshot.messages.single().isQuestion)
        assertNull(RemoteJson.decodeFromString<RemoteSnapshot>("{}").question)
    }

    @Test fun destinationAndRsiStateDecodeIndependentlyOfWorkMode() {
        val snapshot = RemoteJson.decodeFromString<RemoteSnapshot>("""{"viewMode":"rsi","agentMode":"rsi","rsi":{"status":"running","canStart":false,"directionConfig":{"objective":"Improve UI"},"runs":[{"id":"run1","status":"running","iteration":3}]},"conversations":[{"id":"chat1","viewMode":"chat"}]}""")
        assertEquals(RemoteView.RSI, RemoteView.from(snapshot))
        assertEquals("Improve UI", snapshot.rsi!!.directionConfig.objective)
        assertEquals(3, snapshot.rsi!!.runs.single().iteration)
        assertEquals("chat", snapshot.conversations.single().viewMode)
        assertEquals("chat", RemoteCommand.SetViewMode("chat").toJsonObject()["value"]!!.jsonPrimitive.content)
    }

    @Test fun directoryLabelsStayCompactAcrossDesktopPlatforms() {
        assertEquals("…/CODE/conecode", shortRemotePath("/Users/test/Desktop/CODE/conecode/"))
        assertEquals("…/projects/conecode", shortRemotePath("C:\\Users\\test\\projects\\conecode"))
        assertEquals("/", shortRemotePath("/"))
        assertEquals("/home/test", shortRemotePath("/home/test"))
    }

    @Test fun renamedTitlesRemainLiteralJsonData() {
        val title = "a \"title\"\n中文 <b>"
        val command = RemoteCommand.RenameConversation("conversation-1", title).toJsonObject()
        assertEquals(title, command["title"]!!.jsonPrimitive.content)
        assertEquals("conversation-1", command["id"]!!.jsonPrimitive.content)
    }
}
