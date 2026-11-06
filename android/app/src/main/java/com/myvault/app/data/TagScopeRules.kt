package com.myvault.app.data

/** Scope visibility is independent of reusable suggestion preference. */
fun TagEntity.isVisibleInSpace(spaceId: String?): Boolean = when (scope) {
    "global" -> ownerSpaceId == null
    "local" -> spaceId != null && ownerSpaceId == spaceId
    else -> false
}
