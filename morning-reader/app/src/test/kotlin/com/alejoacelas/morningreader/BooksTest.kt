package com.alejoacelas.morningreader

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BooksTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun readsTheServersRequestList() {
        val text = """[{"id":"a","query":"The Yellow Wallpaper","device":"phone","status":"building","title":null,
            "author":null,"source":null,"pack_id":null,"message":null,"created":2,"updated":3}]"""
        val r = json.decodeFromString(ListSerializer(BookRequest.serializer()), text).single()
        assertEquals("building", r.status)
        assertFalse(r.sent)
        assertTrue(r.active)
    }

    @Test fun serverCopiesReplaceLocalOnesAndUnsentStay() {
        val local = listOf(BookRequest("new", "offline one", created = 5), BookRequest("a", "x", created = 2))
        val remote = listOf(BookRequest("a", "x", status = "done", packId = "gutenberg-1", created = 2),
            BookRequest("b", "from the Boox", device = "boox", status = "queued", created = 3))
        val merged = mergeRequests(local, remote)
        assertEquals(listOf("new", "b", "a"), merged.map { it.id })
        assertFalse(merged[0].sent)
        assertTrue(merged[2].sent && !merged[2].active)
    }

    @Test fun fetchesMissingOrResizedPacksOnly() {
        val remote = listOf(RemotePack("same", bytes = 10), RemotePack("resized", bytes = 20), RemotePack("missing", bytes = 5))
        val wanted = packsToFetch(remote, mapOf("same" to 10L, "resized" to 19L, "local-only" to 3L))
        assertEquals(listOf("resized", "missing"), wanted.map { it.id })
    }
}
