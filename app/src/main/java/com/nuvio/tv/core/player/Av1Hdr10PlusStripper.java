package com.nuvio.tv.core.player;

import java.io.ByteArrayOutputStream;

final class Av1Hdr10PlusStripper {

    private static final int OBU_METADATA = 5;
    private static final int METADATA_TYPE_ITUT_T35 = 4;

    // ITU-T T.35 HDR10+ signature: country 0xB5, provider 0x003C, poc 0x0001
    private static final int T35_MIN_PAYLOAD = 5;

    private Av1Hdr10PlusStripper() {}

    /**
     * Removes HDR10+ ITU-T T.35 metadata OBUs from an AV1 temporal unit,
     * writing the result into {@code out} (caller resets it). Returns the
     * number of bytes written, or -1 when nothing was stripped or the data
     * is malformed — in which case {@code out} is left untouched.
     */
    static int stripHdr10PlusObus(byte[] data, int length, ByteArrayOutputStream out) {
        if (!containsHdr10PlusObu(data, length)) return -1;
        int pos = 0;
        int keptStart = 0;
        while (pos < length) {
            int obuStart = pos;
            int header = data[pos++] & 0xFF;
            if ((header & 0x80) != 0) return -1;
            int type = (header >> 3) & 0x0F;
            boolean hasExtension = ((header >> 2) & 0x01) != 0;
            boolean hasSizeField = ((header >> 1) & 0x01) != 0;
            if (hasExtension) {
                if (pos >= length) return -1;
                pos++;
            }
            int payloadSize;
            if (hasSizeField) {
                int size = 0;
                int shift = 0;
                boolean lebDone = false;
                while (pos < length && shift <= 28) {
                    int b = data[pos++] & 0xFF;
                    size |= (b & 0x7F) << shift;
                    if ((b & 0x80) == 0) {
                        lebDone = true;
                        break;
                    }
                    shift += 7;
                }
                if (!lebDone) return -1;
                payloadSize = size;
            } else {
                payloadSize = length - pos;
            }
            if (payloadSize < 0 || pos + payloadSize > length) return -1;
            int payloadStart = pos;
            pos += payloadSize;
            if (type == OBU_METADATA
                    && payloadSize >= 1 + T35_MIN_PAYLOAD
                    && (data[payloadStart] & 0xFF) == METADATA_TYPE_ITUT_T35
                    && isHdr10PlusT35(data, payloadStart + 1, payloadSize - 1)) {
                out.write(data, keptStart, obuStart - keptStart);
                keptStart = pos;
            }
        }
        out.write(data, keptStart, length - keptStart);
        return out.size();
    }

    private static boolean containsHdr10PlusObu(byte[] data, int length) {
        int pos = 0;
        while (pos < length) {
            int header = data[pos++] & 0xFF;
            if ((header & 0x80) != 0) return false;
            int type = (header >> 3) & 0x0F;
            boolean hasExtension = ((header >> 2) & 0x01) != 0;
            boolean hasSizeField = ((header >> 1) & 0x01) != 0;
            if (hasExtension) {
                if (pos >= length) return false;
                pos++;
            }
            int payloadSize;
            if (hasSizeField) {
                int size = 0;
                int shift = 0;
                boolean lebDone = false;
                while (pos < length && shift <= 28) {
                    int b = data[pos++] & 0xFF;
                    size |= (b & 0x7F) << shift;
                    if ((b & 0x80) == 0) {
                        lebDone = true;
                        break;
                    }
                    shift += 7;
                }
                if (!lebDone) return false;
                payloadSize = size;
            } else {
                payloadSize = length - pos;
            }
            if (payloadSize < 0 || pos + payloadSize > length) return false;
            if (type == OBU_METADATA
                    && payloadSize >= 1 + T35_MIN_PAYLOAD
                    && (data[pos] & 0xFF) == METADATA_TYPE_ITUT_T35
                    && isHdr10PlusT35(data, pos + 1, payloadSize - 1)) {
                return true;
            }
            pos += payloadSize;
        }
        return false;
    }

    private static boolean isHdr10PlusT35(byte[] data, int start, int avail) {
        if (avail < T35_MIN_PAYLOAD) return false;
        if ((data[start] & 0xFF) != 0xB5) return false;
        return (data[start + 1] & 0xFF) == 0x00
                && (data[start + 2] & 0xFF) == 0x3C
                && (data[start + 3] & 0xFF) == 0x00
                && (data[start + 4] & 0xFF) == 0x01;
    }
}
