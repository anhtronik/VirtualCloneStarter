package com.digitalizha.apkcloner;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class BinaryXmlPackagePatcher {
    private static final int RES_STRING_POOL_TYPE = 0x0001;
    private static final int RES_XML_START_ELEMENT_TYPE = 0x0102;
    private static final int UTF8_FLAG = 0x00000100;

    private BinaryXmlPackagePatcher() {}

    static final class PatchResult {
        final byte[] manifestBytes;
        final String oldPackageName;
        final boolean labelPatched;

        PatchResult(byte[] manifestBytes, String oldPackageName, boolean labelPatched) {
            this.manifestBytes = manifestBytes;
            this.oldPackageName = oldPackageName;
            this.labelPatched = labelPatched;
        }
    }

    static PatchResult patch(byte[] manifestBytes, String newPackageName, String newLabel) throws IOException {
        if (!isValidPackageName(newPackageName)) {
            throw new IOException("Package clone tidak valid: " + newPackageName);
        }

        int stringPoolOffset = findStringPoolOffset(manifestBytes);
        if (stringPoolOffset < 0) {
            throw new IOException("StringPool AndroidManifest.xml tidak ditemukan.");
        }

        StringPool pool = StringPool.read(manifestBytes, stringPoolOffset);
        int packageStringIndex = findManifestPackageIndex(manifestBytes, pool);
        if (packageStringIndex < 0) {
            throw new IOException("Package name lama tidak bisa dibaca dari AndroidManifest.xml.");
        }

        String oldPackage = pool.strings.get(packageStringIndex);
        if (!isValidPackageName(oldPackage)) {
            throw new IOException("Package name lama tidak valid: " + oldPackage);
        }

        for (int i = 0; i < pool.strings.size(); i++) {
            String value = pool.strings.get(i);
            if (value != null && value.contains(oldPackage)) {
                pool.strings.set(i, value.replace(oldPackage, newPackageName));
            }
        }

        boolean labelPatched = false;
        if (newLabel != null && !newLabel.trim().isEmpty()) {
            int labelIndex = findApplicationLabelIndex(manifestBytes, pool);
            if (labelIndex >= 0 && labelIndex < pool.strings.size()) {
                pool.strings.set(labelIndex, newLabel.trim());
                labelPatched = true;
            }
        }

        byte[] newPoolBytes = pool.toBytes();
        int oldPoolSize = pool.chunkSize;
        byte[] out = new byte[manifestBytes.length - oldPoolSize + newPoolBytes.length];

        System.arraycopy(manifestBytes, 0, out, 0, stringPoolOffset);
        System.arraycopy(newPoolBytes, 0, out, stringPoolOffset, newPoolBytes.length);
        System.arraycopy(
                manifestBytes,
                stringPoolOffset + oldPoolSize,
                out,
                stringPoolOffset + newPoolBytes.length,
                manifestBytes.length - stringPoolOffset - oldPoolSize
        );

        putU32(out, 4, out.length);
        return new PatchResult(out, oldPackage, labelPatched);
    }

    static String readPackageName(byte[] manifestBytes) throws IOException {
        int stringPoolOffset = findStringPoolOffset(manifestBytes);
        if (stringPoolOffset < 0) return null;
        StringPool pool = StringPool.read(manifestBytes, stringPoolOffset);
        int idx = findManifestPackageIndex(manifestBytes, pool);
        return idx >= 0 ? pool.strings.get(idx) : null;
    }

    private static int findStringPoolOffset(byte[] data) throws IOException {
        int offset = 8;
        while (offset + 8 <= data.length) {
            int type = u16(data, offset);
            int size = u32(data, offset + 4);
            if (type == RES_STRING_POOL_TYPE) return offset;
            if (size <= 0) throw new IOException("Chunk size AXML rusak.");
            offset += size;
        }
        return -1;
    }

    private static int findManifestPackageIndex(byte[] data, StringPool pool) throws IOException {
        StartTag tag = findStartTag(data, pool, "manifest");
        if (tag == null) return -1;
        return tag.rawStringIndexForAttribute(pool, "package");
    }

    private static int findApplicationLabelIndex(byte[] data, StringPool pool) throws IOException {
        StartTag tag = findStartTag(data, pool, "application");
        if (tag == null) return -1;
        return tag.rawStringIndexForAttribute(pool, "label");
    }

    private static StartTag findStartTag(byte[] data, StringPool pool, String tagName) throws IOException {
        int offset = 8;
        while (offset + 8 <= data.length) {
            int type = u16(data, offset);
            int size = u32(data, offset + 4);
            if (size <= 0 || offset + size > data.length) {
                throw new IOException("Chunk AXML rusak di offset " + offset);
            }
            if (type == RES_XML_START_ELEMENT_TYPE) {
                StartTag tag = StartTag.read(data, offset);
                String name = pool.safeGet(tag.nameIndex);
                if (tagName.equals(name)) return tag;
            }
            offset += size;
        }
        return null;
    }

    private static boolean isValidPackageName(String value) {
        if (value == null) return false;
        return value.matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+");
    }

    private static int u16(byte[] d, int o) {
        return (d[o] & 0xff) | ((d[o + 1] & 0xff) << 8);
    }

    private static int u32(byte[] d, int o) {
        return (d[o] & 0xff)
                | ((d[o + 1] & 0xff) << 8)
                | ((d[o + 2] & 0xff) << 16)
                | ((d[o + 3] & 0xff) << 24);
    }

    private static void putU16(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >> 8) & 0xff);
    }

    private static void putU32(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >> 8) & 0xff);
        out.write((value >> 16) & 0xff);
        out.write((value >> 24) & 0xff);
    }

    private static void putU32(byte[] out, int offset, int value) {
        out[offset] = (byte) (value & 0xff);
        out[offset + 1] = (byte) ((value >> 8) & 0xff);
        out[offset + 2] = (byte) ((value >> 16) & 0xff);
        out[offset + 3] = (byte) ((value >> 24) & 0xff);
    }

    private static final class StartTag {
        final int nameIndex;
        final int attrBase;
        final int attrSize;
        final int attrCount;

        StartTag(int nameIndex, int attrBase, int attrSize, int attrCount) {
            this.nameIndex = nameIndex;
            this.attrBase = attrBase;
            this.attrSize = attrSize;
            this.attrCount = attrCount;
        }

        static StartTag read(byte[] data, int chunkOffset) throws IOException {
            int extOffset = chunkOffset + 16;
            if (extOffset + 20 > data.length) throw new IOException("StartTag AXML rusak.");
            int nameIndex = u32(data, extOffset + 4);
            int attrStart = u16(data, extOffset + 8);
            int attrSize = u16(data, extOffset + 10);
            int attrCount = u16(data, extOffset + 12);
            int attrBase = extOffset + attrStart;
            return new StartTag(nameIndex, attrBase, attrSize, attrCount);
        }

        int rawStringIndexForAttribute(StringPool pool, String attrName) {
            for (int i = 0; i < attrCount; i++) {
                int off = attrBase + i * attrSize;
                int nameIndex = u32(pool.source, off + 4);
                int rawValueIndex = u32(pool.source, off + 8);
                String name = pool.safeGet(nameIndex);
                if (attrName.equals(name) && rawValueIndex != 0xffffffff) {
                    return rawValueIndex;
                }
            }
            return -1;
        }
    }

    private static final class StringPool {
        final byte[] source;
        final int chunkOffset;
        final int chunkSize;
        final int flags;
        final int styleCount;
        final byte[] styleOffsetsBytes;
        final byte[] styleDataBytes;
        final List<String> strings;

        StringPool(byte[] source, int chunkOffset, int chunkSize, int flags, int styleCount,
                   byte[] styleOffsetsBytes, byte[] styleDataBytes, List<String> strings) {
            this.source = source;
            this.chunkOffset = chunkOffset;
            this.chunkSize = chunkSize;
            this.flags = flags;
            this.styleCount = styleCount;
            this.styleOffsetsBytes = styleOffsetsBytes;
            this.styleDataBytes = styleDataBytes;
            this.strings = strings;
        }

        static StringPool read(byte[] data, int offset) throws IOException {
            int type = u16(data, offset);
            if (type != RES_STRING_POOL_TYPE) throw new IOException("Bukan StringPool.");
            int headerSize = u16(data, offset + 2);
            int size = u32(data, offset + 4);
            int stringCount = u32(data, offset + 8);
            int styleCount = u32(data, offset + 12);
            int flags = u32(data, offset + 16);
            int stringsStart = u32(data, offset + 20);
            int stylesStart = u32(data, offset + 24);

            if (offset + size > data.length) throw new IOException("Ukuran StringPool rusak.");

            int stringOffsetsStart = offset + headerSize;
            int styleOffsetsStart = stringOffsetsStart + stringCount * 4;
            byte[] styleOffsetsBytes = new byte[styleCount * 4];
            if (styleCount > 0) {
                System.arraycopy(data, styleOffsetsStart, styleOffsetsBytes, 0, styleOffsetsBytes.length);
            }

            int stringDataStart = offset + stringsStart;
            int styleDataStart = styleCount > 0 && stylesStart > 0 ? offset + stylesStart : offset + size;
            byte[] styleDataBytes = new byte[Math.max(0, offset + size - styleDataStart)];
            if (styleDataBytes.length > 0) {
                System.arraycopy(data, styleDataStart, styleDataBytes, 0, styleDataBytes.length);
            }

            ArrayList<String> strings = new ArrayList<>();
            boolean utf8 = (flags & UTF8_FLAG) != 0;
            for (int i = 0; i < stringCount; i++) {
                int strOff = u32(data, stringOffsetsStart + i * 4);
                int absolute = stringDataStart + strOff;
                strings.add(utf8 ? decodeUtf8String(data, absolute) : decodeUtf16String(data, absolute));
            }
            return new StringPool(data, offset, size, flags, styleCount, styleOffsetsBytes, styleDataBytes, strings);
        }

        String safeGet(int index) {
            if (index < 0 || index >= strings.size()) return null;
            return strings.get(index);
        }

        byte[] toBytes() throws IOException {
            boolean utf8 = (flags & UTF8_FLAG) != 0;
            ByteArrayOutputStream stringData = new ByteArrayOutputStream();
            int[] offsets = new int[strings.size()];
            for (int i = 0; i < strings.size(); i++) {
                offsets[i] = stringData.size();
                byte[] encoded = utf8 ? encodeUtf8String(strings.get(i)) : encodeUtf16String(strings.get(i));
                stringData.write(encoded);
            }
            align4(stringData);

            int headerSize = 28;
            int stringsStart = headerSize + strings.size() * 4 + styleOffsetsBytes.length;
            int stylesStart = styleCount > 0 ? stringsStart + stringData.size() : 0;
            int newSize = stringsStart + stringData.size() + styleDataBytes.length;

            ByteArrayOutputStream out = new ByteArrayOutputStream(newSize);
            putU16(out, RES_STRING_POOL_TYPE);
            putU16(out, headerSize);
            putU32(out, newSize);
            putU32(out, strings.size());
            putU32(out, styleCount);
            putU32(out, flags);
            putU32(out, stringsStart);
            putU32(out, stylesStart);

            for (int offset : offsets) putU32(out, offset);
            out.write(styleOffsetsBytes);
            out.write(stringData.toByteArray());
            out.write(styleDataBytes);
            return out.toByteArray();
        }
    }

    private static String decodeUtf8String(byte[] data, int offset) throws IOException {
        Len len16 = readUtf8Len(data, offset);
        Len len8 = readUtf8Len(data, offset + len16.bytesRead);
        int start = offset + len16.bytesRead + len8.bytesRead;
        if (start + len8.value > data.length) throw new IOException("String UTF-8 rusak.");
        return new String(data, start, len8.value, StandardCharsets.UTF_8);
    }

    private static String decodeUtf16String(byte[] data, int offset) throws IOException {
        Len len = readUtf16Len(data, offset);
        int start = offset + len.bytesRead;
        int byteLen = len.value * 2;
        if (start + byteLen > data.length) throw new IOException("String UTF-16 rusak.");
        return new String(data, start, byteLen, StandardCharsets.UTF_16LE);
    }

    private static byte[] encodeUtf8String(String value) throws IOException {
        if (value == null) value = "";
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        int utf16Len = value.length();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeUtf8Len(out, utf16Len);
        writeUtf8Len(out, utf8.length);
        out.write(utf8);
        out.write(0);
        return out.toByteArray();
    }

    private static byte[] encodeUtf16String(String value) throws IOException {
        if (value == null) value = "";
        byte[] utf16 = value.getBytes(StandardCharsets.UTF_16LE);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeUtf16Len(out, value.length());
        out.write(utf16);
        out.write(0);
        out.write(0);
        return out.toByteArray();
    }

    private static final class Len {
        final int value;
        final int bytesRead;
        Len(int value, int bytesRead) { this.value = value; this.bytesRead = bytesRead; }
    }

    private static Len readUtf8Len(byte[] data, int offset) {
        int first = data[offset] & 0xff;
        if ((first & 0x80) != 0) {
            int second = data[offset + 1] & 0xff;
            return new Len(((first & 0x7f) << 8) | second, 2);
        }
        return new Len(first, 1);
    }

    private static Len readUtf16Len(byte[] data, int offset) {
        int first = u16(data, offset);
        if ((first & 0x8000) != 0) {
            int second = u16(data, offset + 2);
            return new Len(((first & 0x7fff) << 16) | second, 4);
        }
        return new Len(first, 2);
    }

    private static void writeUtf8Len(ByteArrayOutputStream out, int len) {
        if (len > 0x7f) {
            out.write(((len >> 8) & 0x7f) | 0x80);
            out.write(len & 0xff);
        } else {
            out.write(len & 0xff);
        }
    }

    private static void writeUtf16Len(ByteArrayOutputStream out, int len) {
        if (len > 0x7fff) {
            putU16(out, ((len >> 16) & 0x7fff) | 0x8000);
            putU16(out, len & 0xffff);
        } else {
            putU16(out, len);
        }
    }

    private static void align4(ByteArrayOutputStream out) {
        while ((out.size() % 4) != 0) out.write(0);
    }
}
