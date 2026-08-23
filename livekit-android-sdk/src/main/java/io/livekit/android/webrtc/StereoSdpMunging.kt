package io.livekit.android.webrtc

import android.gov.nist.javax.sdp.fields.AttributeField
import android.javax.sdp.MediaDescription
import android.javax.sdp.SdpException
import android.javax.sdp.SdpFactory
import android.javax.sdp.SdpParseException
import io.livekit.android.util.LKLog
import livekit.org.webrtc.SessionDescription

/**
 * Munges the subscriber's answer so that Opus fmtp lines carry `stereo=1`
 * for all audio mids where the server offer advertised `sprop-stereo=1`.
 * Without this, the native Opus decoder downmixes incoming stereo to mono.
 */
fun SessionDescription.ensureStereoOpus(offer: SessionDescription): SessionDescription {
    val sdpFactory = SdpFactory.getInstance()
    val parsed = try {
        sdpFactory.createSessionDescription(description)
    } catch (e: SdpParseException) {
        LKLog.w { "stereo munging: could not parse answer sdp" }
        return this
    }
    val parsedOffer = try {
        sdpFactory.createSessionDescription(offer.description)
    } catch (e: SdpParseException) {
        LKLog.w { "stereo munging: could not parse offer sdp" }
        return this
    }
    val stereoMids = findStereoMids(parsedOffer)
    if (stereoMids.isEmpty()) {
        return this
    }

    for (mediaDesc in mediaDescriptionsOf(parsed)) {
        val mid: String = try {
            mediaDesc.getAttribute("mid")
        } catch (_: SdpParseException) {
            continue
        } ?: continue
        if (mid !in stereoMids) continue

        val payload: Long = findOpusPayload(mediaDesc) ?: continue
        addStereoToFmtp(mediaDesc, payload)
    }

    return try {
        SessionDescription(type, parsed.toString())
    } catch (_: SdpException) {
        this
    }
}

private fun mediaDescriptionsOf(parsed: android.javax.sdp.SessionDescription): List<MediaDescription> {
    val raw = try {
        parsed.getMediaDescriptions(true)
    } catch (_: SdpException) {
        return emptyList()
    }
    return raw.filterIsInstance<MediaDescription>()
}

private fun findStereoMids(parsed: android.javax.sdp.SessionDescription): List<String> =
    mediaDescriptionsOf(parsed)
        .filter { isPublisherStereo(it) }
        .mapNotNull { midOf(it) }

private fun midOf(mediaDesc: MediaDescription): String? = try {
    mediaDesc.getAttribute("mid")
} catch (_: SdpParseException) {
    null
}

private fun isPublisherStereo(mediaDesc: MediaDescription): Boolean {
    val payload: Long = findOpusPayload(mediaDesc) ?: return false
    return mediaDesc.getFmtps().any { entry ->
        val fmtp: SdpFmtp = entry.second
        fmtp.payload == payload && fmtp.config.split(";").any { cfg ->
            cfg.trim() == "sprop-stereo=1"
        }
    }
}

private fun findOpusPayload(mediaDesc: MediaDescription): Long? {
    for (entry in mediaDesc.getRtps()) {
        val rtp: SdpRtp = entry.second
        if (rtp.codec.equals("opus", ignoreCase = true)) {
            return rtp.payload
        }
    }
    return null
}

private fun addStereoToFmtp(mediaDesc: MediaDescription, payload: Long) {
    var found = false
    for (entry in mediaDesc.getFmtps()) {
        val attrField: AttributeField = entry.first
        val fmtp: SdpFmtp = entry.second
        if (fmtp.payload != payload) continue
        found = true
        var config = fmtp.config
        if (!config.split(";").any { cfg -> cfg.trim() == "stereo=1" }) {
            config = "$config;stereo=1"
            try {
                attrField.setValue("${fmtp.payload} $config")
            } catch (_: SdpException) {
                LKLog.w { "failed to set stereo fmtp" }
            }
        }
        break
    }
    if (!found) {
        mediaDesc.addAttribute(
            SdpFmtp(payload, "stereo=1").toAttributeField(),
        )
    }
}
