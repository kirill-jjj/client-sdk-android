/*
 * Copyright 2023-2024 LiveKit, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Parts of this source code comes from https://github.com/ggarber/sdpparser
 *
 * MIT License
 *
 * Copyright (c) 2023 Gustavo Garcia
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.livekit.android.webrtc

import android.gov.nist.javax.sdp.fields.AttributeField
import android.javax.sdp.MediaDescription
import android.javax.sdp.SdpException
import android.javax.sdp.SdpFactory
import android.javax.sdp.SdpParseException
import io.livekit.android.util.LKLog
import livekit.org.webrtc.SessionDescription

/**
 * @suppress
 */
data class SdpRtp(val payload: Long, val codec: String, val rate: Long?, val encoding: String?)

/**
 * @suppress
 */
fun MediaDescription.getRtps(): List<Pair<AttributeField, SdpRtp>> {
    return getAttributes(true)
        .filterIsInstance<AttributeField>()
        .filter { it.attribute.name == "rtpmap" }
        .mapNotNull {
            val rtp = tryParseRtp(it.value)
            if (rtp == null) {
                LKLog.w { "could not parse rtpmap: ${it.encode()}" }
                return@mapNotNull null
            }
            it to rtp
        }
}

private val RTP = """(\d*) ([\w\-.]*)(?:\s*/(\d*)(?:\s*/(\S*))?)?""".toRegex()
internal fun tryParseRtp(string: String): SdpRtp? {
    val match = RTP.matchEntire(string) ?: return null
    val (payload, codec, rate, encoding) = match.destructured
    return SdpRtp(payload.toLong(), codec, toOptionalLong(rate), toOptionalString(encoding))
}

/**
 * @suppress
 */
data class SdpMsid(
    /** holds the msid-id (and msid-appdata if available) */
    val value: String,
)

/**
 * @suppress
 */
fun MediaDescription.getMsid(): SdpMsid? {
    val attribute = getAttribute("msid") ?: return null
    return SdpMsid(attribute)
}

/**
 * @suppress
 */
data class SdpFmtp(val payload: Long, val config: String) {
    fun toAttributeField(): AttributeField {
        return AttributeField().apply {
            name = "fmtp"
            value = "$payload $config"
        }
    }
}

/**
 * @suppress
 */
fun MediaDescription.getFmtps(): List<Pair<AttributeField, SdpFmtp>> {
    return getAttributes(true)
        .filterIsInstance<AttributeField>()
        .filter { it.attribute.name == "fmtp" }
        .mapNotNull {
            val fmtp = tryParseFmtp(it.value)
            if (fmtp == null) {
                LKLog.w { "could not parse fmtp: ${it.encode()}" }
                return@mapNotNull null
            }
            it to fmtp
        }
}

private val FMTP = """(\d*) ([\S| ]*)""".toRegex()
internal fun tryParseFmtp(string: String): SdpFmtp? {
    val match = FMTP.matchEntire(string) ?: return null
    val (payload, config) = match.destructured
    return SdpFmtp(payload.toLong(), config)
}

/**
 * @suppress
 */
data class SdpExt(val value: Long, val direction: String?, val encryptUri: String?, val uri: String, val config: String?) {
    fun toAttributeField(): AttributeField {
        return AttributeField().apply {
            name = "extmap"
            value = buildString {
                append(this@SdpExt.value)
                if (direction != null) {
                    append(" $direction")
                }
                if (encryptUri != null) {
                    append(" $encryptUri")
                }
                append(" $uri")
                if (config != null) {
                    append(" $config")
                }
            }
        }
    }
}

/**
 * @suppress
 */
fun MediaDescription.getExts(): List<Pair<AttributeField, SdpExt>> {
    return getAttributes(true)
        .filterIsInstance<AttributeField>()
        .filter { it.attribute.name == "extmap" }
        .mapNotNull {
            val ext = tryParseExt(it.value)
            if (ext == null) {
                LKLog.w { "could not parse extmap: ${it.encode()}" }
                return@mapNotNull null
            }
            it to ext
        }
}

private val EXT = """(\d+)(?:/(\w+))?(?: (urn:ietf:params:rtp-hdrext:encrypt))? (\S*)(?: (\S*))?""".toRegex()
internal fun tryParseExt(string: String): SdpExt? {
    val match = EXT.matchEntire(string) ?: return null
    val (value, direction, encryptUri, uri, config) = match.destructured
    return SdpExt(value.toLong(), toOptionalString(direction), toOptionalString(encryptUri), uri, toOptionalString(config))
}

internal fun toOptionalLong(str: String): Long? = if (str.isEmpty()) null else str.toLong()
internal fun toOptionalString(str: String): String? = str.ifEmpty { null }

/**
 * Munges the session description so that Opus fmtp lines carry `stereo=1`
 * for all audio mids where the remote offer advertised `sprop-stereo=1`.
 * Without this, the native Opus decoder downmixes incoming stereo to mono.
 */
fun SessionDescription.ensureStereoOpus(): SessionDescription {
    val sdpFactory = SdpFactory.getInstance()
    val parsed = try {
        sdpFactory.createSessionDescription(description)
    } catch (e: SdpParseException) {
        LKLog.w { "stereo munging: could not parse sdp" }
        return this
    }
    val stereoMids = findStereoMids(parsed)
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
