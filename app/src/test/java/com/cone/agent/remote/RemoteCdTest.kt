package com.cone.agent.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A remote exec runs in a fresh shell and the desktop echoes back the cwd we sent, so `cd`
 * has to be resolved on the phone. These cases pin down that resolution.
 */
class RemoteCdTest {

    private val root = "/Users/me/project"

    @Test
    fun `bare cd returns to the project root`() {
        assertEquals(root, resolveCd("cd", "$root/src", root))
        assertEquals(root, resolveCd("  cd  ", "$root/src", root))
    }

    @Test
    fun `cd dot dot walks up`() {
        assertEquals(root, resolveCd("cd ..", "$root/src", root))
        assertEquals("/Users", resolveCd("cd ../..", root, root))
        assertEquals("/", resolveCd("cd ../../..", root, root))
    }

    @Test
    fun `relative path joins the current directory`() {
        assertEquals("$root/src/app", resolveCd("cd src/app", root, root))
        assertEquals("$root/src/app", resolveCd("cd ./src/./app/", root, root))
    }

    @Test
    fun `absolute path is used as is`() {
        assertEquals("/tmp", resolveCd("cd /tmp", root, root))
        assertEquals("/", resolveCd("cd /", root, root))
    }

    @Test
    fun `tilde means the project root`() {
        assertEquals(root, resolveCd("cd ~", "$root/src", root))
    }

    @Test
    fun `quoted paths keep their spaces`() {
        assertEquals("$root/my docs", resolveCd("cd \"my docs\"", root, root))
        assertEquals("$root/my docs", resolveCd("cd 'my docs'", root, root))
    }

    @Test
    fun `cd dash goes up one level`() {
        assertEquals(root, resolveCd("cd -", "$root/src", root))
    }

    @Test
    fun `anything that is not a standalone cd is left to the desktop`() {
        assertNull(resolveCd("cdrom", root, root))
        assertNull(resolveCd("cdk install", root, root))
        assertNull(resolveCd("cd src && ls", root, root))
        assertNull(resolveCd("echo cd", root, root))
        assertNull(resolveCd("", root, root))
    }

    @Test
    fun `without any base directory nothing is resolved`() {
        assertNull(resolveCd("cd ..", null, null))
    }
}
