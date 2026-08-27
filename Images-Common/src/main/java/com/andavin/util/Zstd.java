/*
 * MIT License
 *
 * Copyright (c) 2020 Mark
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
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.andavin.util;

import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * A small wrapper around {@link ZstdInputStream}/{@link ZstdOutputStream}
 * that can be enabled or disabled at runtime.
 * <p>
 * Compressed output is prefixed with a 4 byte magic header
 * ({@code IMZC}) so that pre-existing (uncompressed) data can be
 * detected and read without issues.
 *
 * @since November 13, 2023
 * @author Andavin
 */
public final class Zstd {

    private static final byte[] MAGIC = {'I', 'M', 'Z', 'C'};
    private static final int DEFAULT_LEVEL = 3;
    private static int level = DEFAULT_LEVEL;
    private static boolean enabled = true;

    private Zstd() {
    }

    /**
     * Set the compression level to use (1-22, higher = smaller output).
     *
     * @param level The level in the range {@code 1} to {@code 22}.
     */
    public static void setLevel(int level) {
        Zstd.level = Math.max(1, Math.min(22, level));
    }

    /**
     * Enable or disable compression. When disabled, {@link #compress(byte[])}
     * will return the input as-is and {@link #decompress(byte[])} only
     * decompresses data that was previously compressed with compression enabled.
     *
     * @param enabled Whether compression should be enabled.
     */
    public static void setEnabled(boolean enabled) {
        Zstd.enabled = enabled;
    }

    /**
     * If compression is currently enabled.
     *
     * @return If compression will be applied.
     */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Test if the given data has the {@code IMZC} magic header,
     * meaning it was compressed by this class.
     *
     * @param data The data to test.
     * @return If the data is compressed in the format used here.
     */
    public static boolean isCompressed(byte[] data) {

        if (data == null || data.length < MAGIC.length) {
            return false;
        }

        for (int i = 0; i < MAGIC.length; i++) {
            if (data[i] != MAGIC[i]) {
                return false;
            }
        }

        return true;
    }

    /**
     * Wrap the given output stream so that all data written to it is
     * compressed with zstd (prefixed with the magic header) unless
     * compression is disabled, in which case the raw stream is returned.
     *
     * @param out The stream to wrap.
     * @return The wrapped stream.
     * @throws IOException If the magic header cannot be written.
     */
    public static OutputStream wrap(OutputStream out) throws IOException {

        if (!enabled) {
            return out;
        }

        out.write(MAGIC, 0, MAGIC.length);
        return new ZstdOutputStream(out, level);
    }

    /**
     * Compress the given data with zstd unless compression is
     * disabled or the data is empty. The output, when compressed,
     * is prefixed with the magic header.
     *
     * @param data The data to compress.
     * @return The compressed data (or the input if compression is disabled).
     * @throws IOException If an error occurs while compressing.
     */
    public static byte[] compress(byte[] data) throws IOException {

        if (!enabled || data == null || data.length == 0) {
            return data;
        }

        ByteArrayOutputStream byteStream = new ByteArrayOutputStream(data.length / 2);
        byteStream.write(MAGIC, 0, MAGIC.length);
        try (ZstdOutputStream stream = new ZstdOutputStream(byteStream, level)) {
            stream.write(data);
        }

        return byteStream.toByteArray();
    }

    /**
     * Decompress the given data if it was compressed by {@link #compress(byte[])}.
     * Data without the magic header is returned as-is so pre-existing
     * storage remains readable.
     *
     * @param data The possibly compressed data.
     * @return The decompressed data.
     * @throws IOException If an error occurs while decompressing.
     */
    public static byte[] decompress(byte[] data) throws IOException {

        if (data == null || !isCompressed(data)) {
            return data;
        }

        try (ZstdInputStream stream = new ZstdInputStream(new ByteArrayInputStream(
                data, MAGIC.length, data.length - MAGIC.length))) {
            ByteArrayOutputStream byteStream = new ByteArrayOutputStream(data.length * 4);
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                byteStream.write(buffer, 0, count);
            }
            return byteStream.toByteArray();
        }
    }
}