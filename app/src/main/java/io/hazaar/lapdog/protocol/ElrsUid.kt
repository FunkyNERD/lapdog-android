package io.hazaar.lapdog.protocol

import java.security.MessageDigest

/**
 * Bind phrase -> 6-byte ELRS UID (the timer's ESP-NOW MAC), the same derivation
 * as vrxc-msp's elrs_uid.h: MD5 of `-DMY_BINDING_PHRASE="<phrase>"`, first six
 * bytes, low bit of byte 0 cleared.
 */
object ElrsUid {
    fun fromBindPhrase(phrase: String): ByteArray {
        val md5 = MessageDigest.getInstance("MD5").digest("-DMY_BINDING_PHRASE=\"$phrase\"".toByteArray())
        val uid = md5.copyOf(6)
        uid[0] = (uid[0].toInt() and 0xFE).toByte()
        return uid
    }

    fun format(uid: ByteArray) = uid.joinToString(":") { "%02x".format(it) }
}
