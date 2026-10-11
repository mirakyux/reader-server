package io.legado.app.model.analyzeRule;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Deterministic, generated TrueType tables: one simple and one composite glyph. */
public final class FontFixture {
    private FontFixture() { }

    public static byte[] font(int codePoint, int cmapFormat) {
        Map<String, byte[]> tables = new LinkedHashMap<>();
        ByteBuffer head = buffer(54);
        head.putInt(0x00010000).putInt(0x00010000).putInt(0).putInt(0x5F0F3CF5)
                .putShort((short) 0).putShort((short) 1000).putLong(0).putLong(0)
                .putShort((short) 0).putShort((short) 0).putShort((short) 10).putShort((short) 10)
                .putShort((short) 0).putShort((short) 8).putShort((short) 2).putShort((short) 1).putShort((short) 0);
        tables.put("head", head.array());
        ByteBuffer maxp = buffer(32);
        maxp.putInt(0x00010000).putShort((short) 3).putShort((short) 3).putShort((short) 1);
        tables.put("maxp", maxp.array());

        ByteBuffer simple = buffer(30);
        simple.putShort((short) 1).putShort((short) 0).putShort((short) 0).putShort((short) 10).putShort((short) 10)
                .putShort((short) 2).putShort((short) 0).put(new byte[]{1, 1, 1})
                .putShort((short) 0).putShort((short) 10).putShort((short) -10)
                .putShort((short) 0).putShort((short) 0).putShort((short) 10);
        ByteBuffer composite = buffer(20);
        composite.putShort((short) -1).putShort((short) 0).putShort((short) 0).putShort((short) 10).putShort((short) 10)
                .putShort((short) 11).putShort((short) 1).putShort((short) -3).putShort((short) 4).putShort((short) -8192);
        tables.put("glyf", buffer(50).put(simple.array()).put(composite.array()).array());
        tables.put("loca", buffer(16).putInt(0).putInt(0).putInt(30).putInt(50).array());

        ByteBuffer subtable;
        if (cmapFormat == 4) {
            subtable = buffer(36);
            subtable.putShort((short) 4).putShort((short) 36).putShort((short) 0)
                    .putShort((short) 4).putShort((short) 4).putShort((short) 1).putShort((short) 0)
                    .putShort((short) codePoint).putShort((short) 0xFFFF).putShort((short) 0)
                    .putShort((short) codePoint).putShort((short) 0xFFFF)
                    .putShort((short) -1).putShort((short) 1)
                    .putShort((short) 4).putShort((short) 0)
                    .putShort((short) 2).putShort((short) 0);
        } else if (cmapFormat == 6) {
            subtable = buffer(14);
            subtable.putShort((short) 6).putShort((short) 14).putShort((short) 0)
                    .putShort((short) codePoint).putShort((short) 2).putShort((short) 1).putShort((short) 2);
        } else if (cmapFormat == 10) {
            subtable = buffer(24);
            subtable.putShort((short) 10).putShort((short) 0).putInt(24).putInt(0)
                    .putInt(codePoint).putInt(2).putShort((short) 1).putShort((short) 2);
        } else {
            subtable = buffer(28);
            subtable.putShort((short) cmapFormat).putShort((short) 0).putInt(28).putInt(0).putInt(1)
                    .putInt(codePoint).putInt(codePoint + 1).putInt(1);
        }
        tables.put("cmap", buffer(12 + subtable.capacity()).putShort((short) 0).putShort((short) 1)
                .putShort((short) 3).putShort((short) (cmapFormat >= 10 ? 10 : 1)).putInt(12).put(subtable.array()).array());
        byte[] name = "Fixture Font".getBytes(StandardCharsets.UTF_16BE);
        tables.put("name", buffer(18 + name.length).putShort((short) 0).putShort((short) 1).putShort((short) 18)
                .putShort((short) 3).putShort((short) 1).putShort((short) 0x409).putShort((short) 1)
                .putShort((short) name.length).putShort((short) 0).put(name).array());

        int offset = 12 + tables.size() * 16;
        int size = offset + tables.values().stream().mapToInt(bytes -> (bytes.length + 3) & ~3).sum();
        ByteBuffer result = buffer(size);
        result.putInt(0x00010000).putShort((short) tables.size()).putShort((short) 64).putShort((short) 2).putShort((short) 48);
        for (Map.Entry<String, byte[]> table : tables.entrySet()) {
            result.put(table.getKey().getBytes(StandardCharsets.US_ASCII)).putInt(0).putInt(offset).putInt(table.getValue().length);
            int next = offset + ((table.getValue().length + 3) & ~3);
            ByteBuffer contents = result.duplicate();
            contents.position(offset);
            contents.put(table.getValue());
            offset = next;
        }
        return result.array();
    }

    private static ByteBuffer buffer(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
    }
}
