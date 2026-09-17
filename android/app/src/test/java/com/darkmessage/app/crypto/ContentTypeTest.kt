package com.darkmessage.app.crypto

import com.darkmessage.app.data.model.ContentType
import com.darkmessage.app.data.model.PayloadVersion
import org.junit.Assert.*
import org.junit.Test

class ContentTypeTest {

    @Test
    fun `ContentType fromCode returns correct values`() {
        assertEquals(ContentType.TEXT, ContentType.fromCode(0x01))
        assertEquals(ContentType.IMAGE, ContentType.fromCode(0x02))
        assertEquals(ContentType.DOCUMENT, ContentType.fromCode(0x03))
    }

    @Test
    fun `ContentType fromCode returns null for unknown codes`() {
        assertNull(ContentType.fromCode(0x00))
        assertNull(ContentType.fromCode(0x04))
        assertNull(ContentType.fromCode(0xFF.toByte()))
    }

    @Test
    fun `PayloadVersion fromCode returns V1`() {
        assertEquals(PayloadVersion.V1, PayloadVersion.fromCode(0x01))
    }

    @Test
    fun `PayloadVersion fromCode returns null for unknown`() {
        assertNull(PayloadVersion.fromCode(0x00))
        assertNull(PayloadVersion.fromCode(0x02))
    }

    @Test
    fun `all ContentType codes are unique`() {
        val codes = ContentType.entries.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
    }
}
