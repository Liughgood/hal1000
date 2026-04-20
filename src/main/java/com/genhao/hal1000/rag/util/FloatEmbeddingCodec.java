package com.genhao.hal1000.rag.util;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class FloatEmbeddingCodec {

    private FloatEmbeddingCodec() {
    }

    public static byte[] floatsToBytesLittleEndian(float[] values) {
        var bb = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : values) {
            bb.putFloat(v);
        }
        return bb.array();
    }

    public static float[] bytesToFloatsLittleEndian(byte[] blob) {
        var bb = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
        int n = blob.length / 4;
        var out = new float[n];
        for (int i = 0; i < n; i++) {
            out[i] = bb.getFloat();
        }
        return out;
    }
}
