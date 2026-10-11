package io.legado.app.model.analyzeRule;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QueryTTFCompatibilityTests {
    @Test
    void fuGlyphMethodsAndLegacyAliasesShareTheSameMappings() {
        QueryTTF font = new QueryTTF(FontFixture.font(0x4E00, 12));
        assertEquals("0,0|10,0|-10,10", font.getGlyfById(1));
        assertEquals(font.getGlyfById(1), font.getGlyfByUnicode(0x4E00));
        assertEquals(font.getGlyfById(1), font.getGlyfByCode(0x4E00));
        assertEquals(1, font.getGlyfIdByUnicode(0x4E00));
        assertEquals(0x4E00, font.getUnicodeByGlyf(font.getGlyfById(1)));
        assertEquals(0x4E00, font.getCodeByGlyf(font.getGlyfById(1)));
        assertEquals("Fixture Font", font.getNameById(1));
        assertTrue(font.inLimit((char) 0x4E00));
        assertTrue(font.inLimit((char) 0x4E01));
        assertFalse(font.inLimit((char) 0x4E02));
        assertNull(font.getGlyfById(-1));
        assertNull(font.getGlyfById(3));
        assertNull(font.getGlyfByUnicode(0x4E02));
        assertEquals("", font.getGlyfByCode(0x4E02));
        assertEquals(0, font.getUnicodeByGlyf(null));
        assertEquals(0, font.getGlyfIdByUnicode(0x4E02));
        assertEquals("error", font.getNameById(999));
    }

    @Test
    void fullUnicodeCmapFormatsRetainSupplementaryCodePoints() {
        for (int format : new int[]{10, 12, 13}) {
            QueryTTF font = new QueryTTF(FontFixture.font(0x20000, format));
            assertEquals(1, font.getGlyfIdByUnicode(0x20000), "format " + format);
            assertEquals(format == 13 ? 1 : 2, font.getGlyfIdByUnicode(0x20001));
            assertNotNull(font.getGlyfByUnicode(0x20000));
            assertTrue(font.inLimit(0x20000));
        }
    }

    @Test
    void bmpCmapHandlesRangeOffsetsAndSignedDeltas() {
        for (int format : new int[]{4, 6}) {
            QueryTTF font = new QueryTTF(FontFixture.font(0x4E00, format));
            assertEquals(1, font.getGlyfIdByUnicode(0x4E00));
            assertEquals("0,0|10,0|-10,10", font.getGlyfByUnicode(0x4E00));
            assertEquals(0, font.getGlyfIdByUnicode(0xFFFF));
        }
    }

    @Test
    void compositeGlyphsRetainFuFieldsAndSignedTransform() {
        QueryTTF font = new QueryTTF(FontFixture.font(0x4E00, 12));
        assertEquals("[{flags:11,glyphIndex:1,arg1:-3,arg2:4,xScale:-0.5,scale01:0.0,scale10:0.0,yScale:-0.5}]",
                font.getGlyfById(2));
        assertEquals(0x4E01, font.getUnicodeByGlyf(font.getGlyfById(2)));
        assertTrue(font.isBlankUnicode(0x200B));
        assertTrue(font.isBlankUnicode(' '));
        assertFalse(font.isBlankUnicode(0x20000));
    }

    @Test
    void malformedFontRangesAreRejectedBeforeAccess() {
        assertThrows(IllegalArgumentException.class, () -> new QueryTTF(new byte[0]));
        byte[] font = FontFixture.font(0x20000, 12);
        font[20] = (byte) 0xFF;
        assertThrows(IllegalArgumentException.class, () -> new QueryTTF(font));
    }
}
