package com.myvault.app.data

import org.junit.Assert.assertThrows
import org.junit.Test

class TagScopeBackupTest {
    @Test fun legacyTagDefaultsToGlobal() {
        validateTagScopes(ExportPayload(tags = listOf(TagDto(id = "t", name = "Legacy", reusable = false))))
    }

    @Test fun rejectsMissingOwnerAndCrossSpaceAttachment() {
        val local = TagDto(id = "t", name = "Ideas", scope = "local", ownerSpaceId = "a")
        assertThrows(IllegalArgumentException::class.java) {
            validateTagScopes(ExportPayload(version = 2, tags = listOf(local)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateTagScopes(ExportPayload(version = 2,
                spaces = listOf(SpaceDto(id = "a", name = "A"), SpaceDto(id = "b", name = "B")),
                entries = listOf(EntryDto(id = "e", spaceId = "b", tags = listOf(local)))))
        }
    }

    @Test fun preservesLegacySameNamedTagDefinitionsById() {
        val first = TagDto(id = "old-1", name = "Ideas", reusable = false)
        val second = TagDto(id = "old-2", name = "ideas", reusable = true)
        validateTagScopes(ExportPayload(version = 2, tags = listOf(first, second)))
    }

    @Test fun rejectsConflictingDefinitionsAndFutureVersions() {
        val tag = TagDto(id = "t", name = "Ideas")
        assertThrows(IllegalArgumentException::class.java) {
            validateTagScopes(ExportPayload(version = 2, tags = listOf(tag, tag.copy(name = "Other"))))
        }
        assertThrows(IllegalArgumentException::class.java) { validateTagScopes(ExportPayload(version = 3)) }
    }
}
