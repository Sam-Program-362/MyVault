package com.myvault.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TagScopeRulesTest {
    @Test
    fun globalTagsAreVisibleInEverySpaceAndReusableIsIndependent() {
        val reusable = TagEntity(id = "g1", name = "Shared", reusable = true)
        val oneOff = reusable.copy(id = "g2", reusable = false)
        assertTrue(reusable.isVisibleInSpace("space-a"))
        assertTrue(reusable.isVisibleInSpace("space-b"))
        assertTrue(reusable.isVisibleInSpace(null))
        assertTrue(oneOff.isVisibleInSpace("space-a"))
    }

    @Test
    fun sameNamedLocalTagsAreVisibleOnlyInTheirOwnerSpace() {
        val localA = TagEntity(id = "a", name = "Ideas", scope = "local", ownerSpaceId = "space-a")
        val localB = TagEntity(id = "b", name = "Ideas", scope = "local", ownerSpaceId = "space-b")
        assertTrue(localA.isVisibleInSpace("space-a"))
        assertFalse(localA.isVisibleInSpace("space-b"))
        assertFalse(localA.isVisibleInSpace(null))
        assertTrue(localB.isVisibleInSpace("space-b"))
        assertFalse(localB.isVisibleInSpace("space-a"))
    }

    @Test
    fun malformedScopeOwnershipFailsClosed() {
        assertFalse(TagEntity(id = "bad", name = "Bad", scope = "global", ownerSpaceId = "space-a").isVisibleInSpace("space-a"))
        assertFalse(TagEntity(id = "bad2", name = "Bad", scope = "unknown").isVisibleInSpace(null))
    }
}
