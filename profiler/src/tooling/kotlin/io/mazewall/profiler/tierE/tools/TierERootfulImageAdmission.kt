package io.mazewall.profiler.tierE.tools

/** A pinned image has no implicit rebuild or tag-based pull; legacy label policy is unchanged. */
public object TierERootfulImageAdmission {
    public data class Selection(
        val imageReference: String,
        val rebuild: Boolean,
    )

    public fun select(
        tag: String,
        currentImageId: String?,
        containerfileLabel: String?,
        containerfileSha256: String,
        expectedImageId: String?,
    ): Selection {
        require(tag.isNotBlank() && containerfileSha256.matches(Regex("[a-f0-9]{64}"))) { "Invalid rootful image context" }
        if (expectedImageId != null) {
            require(expectedImageId.matches(Regex("sha256:[a-f0-9]{64}"))) { "Invalid pinned rootful image ID" }
            check(currentImageId == expectedImageId) { "Pinned rootful image ID differs from predeclared source: $currentImageId" }
            return Selection(expectedImageId, rebuild = false)
        }
        return Selection(tag, rebuild = currentImageId == null || containerfileLabel != containerfileSha256)
    }
}
