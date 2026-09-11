package com.cone.agent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentWebPolicyTest {
    @Test fun keywordsAndInvalidUrlsCannotBecomeSearches() {
        listOf("今日新闻", "weather Beijing", "", "https://", "file:///tmp/page.html", "example.com/a b")
            .forEach { assertNull(it, AgentWebPolicy.directUrl(it)) }
    }

    @Test fun supportedSearchEngineLinksAreRejected() {
        listOf(
            "https://www.bing.com/search?q=test",
            "https://cn.bing.com/search/?q=test",
            "https://www.baidu.com/s?wd=test",
            "https://www.google.com/search?q=test",
            "https://www.google.com.hk/webhp?q=test",
            "https://www.google.com/",
        ).forEach { assertNull(it, AgentWebPolicy.directUrl(it)) }
    }

    @Test fun taskPagesAndAppSpecificSearchStillWork() {
        assertEquals("https://example.com/docs", AgentWebPolicy.directUrl(" example.com/docs "))
        listOf(
            "https://example.com/search?q=product",
            "https://www.google.com/maps",
            "http://192.168.1.2:8080/dashboard",
        ).forEach { assertEquals(it, AgentWebPolicy.directUrl(it)) }
    }
}
