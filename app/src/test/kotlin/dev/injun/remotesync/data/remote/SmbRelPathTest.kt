package dev.injun.remotesync.data.remote

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [SmbRelPath] is the SMB-side counterpart of the local traversal guard: every path
 * handed to [SmbRemoteStorage] goes through it before being joined to rootPath and
 * converted to '\' separators, so a regression here would let a crafted local
 * filename write outside the sync root on the share.
 */
class SmbRelPathTest {

    @Test
    fun `accepts ordinary relative paths`() {
        assertTrue(SmbRelPath.isSafe(""))
        assertTrue(SmbRelPath.isSafe("db.kdbx"))
        assertTrue(SmbRelPath.isSafe("vault/db.kdbx"))
        assertTrue(SmbRelPath.isSafe("a/b/c.txt"))
        assertTrue(SmbRelPath.isSafe(".hidden"))
        assertTrue(SmbRelPath.isSafe("dots..in..name.txt"))
    }

    @Test
    fun `rejects dot and dot-dot segments`() {
        assertFalse(SmbRelPath.isSafe("."))
        assertFalse(SmbRelPath.isSafe(".."))
        assertFalse(SmbRelPath.isSafe("../escape.txt"))
        assertFalse(SmbRelPath.isSafe("a/../escape.txt"))
        assertFalse(SmbRelPath.isSafe("a/.."))
        assertFalse(SmbRelPath.isSafe("./a"))
    }

    @Test
    fun `rejects empty segments`() {
        assertFalse(SmbRelPath.isSafe("/abs"))
        assertFalse(SmbRelPath.isSafe("a//b"))
        assertFalse(SmbRelPath.isSafe("a/"))
    }

    @Test
    fun `rejects backslash smuggling`() {
        // '\' is a legal character in a local filename but the path separator on
        // SMB, so "..\x" inside one '/'-segment would climb out on the server.
        assertFalse(SmbRelPath.isSafe("a\\b"))
        assertFalse(SmbRelPath.isSafe("..\\escape.txt"))
        assertFalse(SmbRelPath.isSafe("a/..\\..\\escape.txt"))
    }
}
