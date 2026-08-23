package io.livekit.android.webrtc

import android.gov.nist.javax.sdp.fields.AttributeField
import android.javax.sdp.MediaDescription
import android.javax.sdp.SdpException
import android.javax.sdp.SdpFactory
import android.javax.sdp.SdpParseException
import io.livekit.android.util.LKLog
import livekit.org.webrtc.SessionDescription

private const val OPUS_CODEC = "opus"
private const val STEREO_FMTP_PARAM = "stereo=1"
private const val SPROP_STEREO_FMTP_PARAM = "sprop-stereo=1"
private const val MID_ATTRIBUTE = "mid"

/**
 * Adds `stereo=1` to the Opus fmtp line of the answer for every audio media
 * section where [offer] advertised `sprop-stereo=1`.
 *
 * The native Opus decoder downmixes incoming stereo packets to mono unless the
 * local answer negotiates `stereo=1`, so without this munging a stereo track
 * published by another participant is received as mono.
 *
 * Mirrors the behavior of client-sdk-js, which extracts `remoteStereoMids` from
 * the server offer and rewrites the matching fmtp lines when creating the answer.
 *
 * @suppress
 */
internal fun SessionDescription.ensureStereoOpus(offer: SessionDescription): SessionDescription {
    val sdpFactory = SdpFactory.getInstance()
    val parsedAnswer = parseSessionDescription(sdpFactory, description) ?: return this
    val parsedOffer = parseSessionDescription(sdpFactory, offer.description) ?: return this

    val stereoMids = findStereoMids(parsedOffer)
    if (stereoMids.isEmpty()) {
        return this
    }

    for (mediaDesc in mediaDescriptionsOf(parsedAnswer)) {
        val mid = midOf(mediaDesc) ?: continue
        if (mid !in stereoMids) continue

        val payloadType = findOpusPayloadType(mediaDesc) ?: continue
        ensureStereoFmtpParam(mediaDesc, payloadType)
    }

    return try {
        SessionDescription(type, parsedAnswer.toString())
    } catch (_: SdpException) {
        this
    }
}

private fun parseSessionDescription(
    sdpFactory: SdpFactory,
    description: String,
): android.javax.sdp.SessionDescription? = try {
    sdpFactory.createSessionDescription(description)
} catch (_: SdpParseException) {
    LKLog.w { "stereo munging: could not parse sdp" }
    null
}

private fun mediaDescriptionsOf(parsed: android.javax.sdp.SessionDescription): List<MediaDescription> {
    val raw = try {
        parsed.getMediaDescriptions(true)
    } catch (_: SdpException) {
        return emptyList()
    }
    return raw.filterIsInstance<MediaDescription>()
}

private fun findStereoMids(offer: android.javax.sdp.SessionDescription): List<String> =
    mediaDescriptionsOf(offer)
        .filter { mediaDesc -> isPublisherStereo(mediaDesc) }
        .mapNotNull(::midOf)

private fun midOf(mediaDesc: MediaDescription): String? = try {
    mediaDesc.getAttribute(MID_ATTRIBUTE)
} catch (_: SdpParseException) {
    null
}

private fun isPublisherStereo(mediaDesc: MediaDescription): Boolean {
    val payloadType = findOpusPayloadType(mediaDesc) ?: return false
    return mediaDesc.getFmtps().any { (_, fmtp) ->
        fmtp.payload == payloadType && fmtp.hasFmtpParam(SPROP_STEREO_FMTP_PARAM)
    }
}

private fun findOpusPayloadType(mediaDesc: MediaDescription): Long? =
    mediaDesc.getRtps()
        .firstOrNull { (_, rtp) -> rtp.codec.equals(OPUS_CODEC, ignoreCase = true) }
        ?.second
        ?.payload

private fun SdpFmtp.hasFmtpParam(param: String): Boolean =
    config.split(";").any { it.trim() == param }

private fun ensureStereoFmtpParam(mediaDesc: MediaDescription, payloadType: Long) {
    val existing = mediaDesc.getFmtps().firstOrNull { (_, fmtp) -> fmtp.payload == payloadType }
    if (existing == null) {
        mediaDesc.addAttribute(
            SdpFmtp(payloadType, STEREO_FMTP_PARAM).toAttributeField(),
        )
        return
    }

    val (attributeField, fmtp) = existing
    if (fmtp.hasFmtpParam(STEREO_FMTP_PARAM)) {
        return
    }
    try {
        attributeField.setValue("${fmtp.payload} ${fmtp.config};$STEREO_FMTP_PARAM")
    } catch (_: SdpException) {
        LKLog.w { "stereo munging: failed to update opus fmtp line" }
    }
}
