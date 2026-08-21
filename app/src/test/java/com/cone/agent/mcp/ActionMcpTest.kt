package com.cone.agent.mcp

import com.cone.agent.agent.action.ActionType
import org.junit.Assert.*
import org.junit.Test

class ActionMcpTest {
    @Test fun mcpCallWire() {
        assertEquals("mcp_call", ActionType.MCP_CALL.wire)
        assertEquals(ActionType.MCP_CALL, ActionType.from("mcp_call"))
        assertEquals(ActionType.MCP_CALL, ActionType.from("MCP_CALL"))
    }
    @Test fun mcpCallIsHeadless() {
        assertTrue(ActionType.HEADLESS.contains(ActionType.MCP_CALL))
    }
    @Test fun unknownStillUnknown() {
        assertEquals(ActionType.UNKNOWN, ActionType.from("nonexistent"))
    }
}
