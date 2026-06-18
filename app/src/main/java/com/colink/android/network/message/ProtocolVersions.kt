package com.colink.android.network.message

internal const val REASON_PROTOCOL_MAJOR_MISMATCH = "colink:protocol.major_mismatch.v1"
internal const val REASON_PROTOCOL_INVALID_VERSION = "colink:protocol.invalid_version.v1"
internal const val REASON_BUSINESS_MAJOR_MISMATCH = "colink:business.major_mismatch.v1"
internal const val REASON_BUSINESS_INVALID_VERSION = "colink:business.invalid_version.v1"

internal data class VersionCompatibility(
    val compatible: Boolean,
    val reason: String? = null,
    val message: String? = null,
)

internal fun checkLanProtocolVersion(peerVersion: String): VersionCompatibility =
    checkSemanticMajor(
        localVersion = LAN_PROTOCOL_VERSION,
        peerVersion = peerVersion,
        invalidReason = REASON_PROTOCOL_INVALID_VERSION,
        mismatchReason = REASON_PROTOCOL_MAJOR_MISMATCH,
        label = "LAN protocol",
    )

internal fun checkBusinessProtocolVersion(peerVersion: String): VersionCompatibility =
    checkSemanticMajor(
        localVersion = BUSINESS_PROTOCOL_VERSION,
        peerVersion = peerVersion,
        invalidReason = REASON_BUSINESS_INVALID_VERSION,
        mismatchReason = REASON_BUSINESS_MAJOR_MISMATCH,
        label = "Business protocol",
    )

internal fun supportsLanKeyExchange(peerVersion: String): Boolean =
    parseSemver(LAN_PROTOCOL_VERSION)?.let { local ->
        parseSemver(peerVersion)?.let { peer ->
            local.major == peer.major && local >= Semver(1, 1, 0) && peer >= Semver(1, 1, 0)
        }
    } == true

internal fun supportsBusinessProtocolAtLeast(peerVersion: String, major: Int, minor: Int, patch: Int = 0): Boolean =
    parseSemver(BUSINESS_PROTOCOL_VERSION)?.let { local ->
        parseSemver(peerVersion)?.let { peer ->
            val required = Semver(major, minor, patch)
            local.major == peer.major && peer >= required && local >= required
        }
    } == true

internal fun negotiatedLanProtocolVersion(peerVersion: String): String {
    val local = parseSemver(LAN_PROTOCOL_VERSION) ?: return LAN_PROTOCOL_VERSION
    val peer = parseSemver(peerVersion) ?: return LAN_PROTOCOL_VERSION
    return minOf(local, peer).wire
}

private fun checkSemanticMajor(
    localVersion: String,
    peerVersion: String,
    invalidReason: String,
    mismatchReason: String,
    label: String,
): VersionCompatibility {
    val local = parseSemver(localVersion)
        ?: return VersionCompatibility(false, invalidReason, "$label local version is invalid")
    val peer = parseSemver(peerVersion)
        ?: return VersionCompatibility(false, invalidReason, "$label peer version is invalid")
    if (local.major != peer.major) {
        return VersionCompatibility(
            compatible = false,
            reason = mismatchReason,
            message = "$label major version ${peer.major} is incompatible with local major version ${local.major}",
        )
    }
    return VersionCompatibility(true)
}

private data class Semver(val major: Int, val minor: Int, val patch: Int) : Comparable<Semver> {
    val wire: String
        get() = "$major.$minor.$patch"

    override fun compareTo(other: Semver): Int =
        compareValuesBy(this, other, Semver::major, Semver::minor, Semver::patch)
}

private val semverPattern = Regex("""^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$""")

private fun parseSemver(value: String): Semver? {
    val match = semverPattern.matchEntire(value.trim()) ?: return null
    return Semver(
        major = match.groupValues[1].toIntOrNull() ?: return null,
        minor = match.groupValues[2].toIntOrNull() ?: return null,
        patch = match.groupValues[3].toIntOrNull() ?: return null,
    )
}
