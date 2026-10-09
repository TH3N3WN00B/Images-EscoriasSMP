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
package com.andavin.images.image;

import com.andavin.util.Logger;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * Decodes image files into {@link BufferedImage}s using the standard
 * Java {@link ImageIO} readers (PNG, JPEG, GIF, BMP, WBMP and WebP)
 * falling back to {@link Ffmpeg} for any format that ImageIO cannot
 * read (JPEG XL, WebM and any other arbitrary format).
 *
 * @since November 13, 2023
 * @author Andavin
 */
public final class ImageDecoder {

    /**
     * The maximum amount of pixels that a source image may have. Larger
     * images are rejected before they are decoded since a tiny file can
     * declare enormous dimensions and exhaust the server memory.
     */
    private static final long MAX_PIXELS = 64L * 1024 * 1024;

    private ImageDecoder() {
    }

    /**
     * Read only the header of the image (when ImageIO knows the format)
     * and make sure that the declared dimensions are reasonable.
     */
    private static void checkDimensions(File file) throws IOException {

        try (ImageInputStream in = ImageIO.createImageInputStream(file)) {

            if (in == null) {
                return;
            }

            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return; // Not an ImageIO format; ffmpeg handles it
            }

            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels > MAX_PIXELS) {
                    throw new IOException("Image is too large (" + reader.getWidth(0) + "x" +
                            reader.getHeight(0) + ") for " + file.getName());
                }
            } finally {
                reader.dispose();
            }
        }
    }

    /**
     * Decode the given image file into a {@link BufferedImage}.
     * <p>
     * The source is always losslessly re-compressed with ffmpeg first
     * (when it is enabled and a suitable encoder is available) and the
     * optimized copy is decoded instead, so re-using a source never
     * decodes a larger file than it has to. If the source cannot be
     * improved it is decoded as-is.
     *
     * @param file The file to decode.
     * @return The decoded image.
     * @throws IOException If the file does not exist or there is
     *                     no reader available for the file.
     */
    public static BufferedImage decode(File file) throws IOException {

        if (file == null || !file.isFile()) {
            throw new IOException("Image file does not exist: " + file);
        }

        // Losslessly re-compress the source with the command for its
        // format (if enabled) and decode the optimized copy instead
        File optimized = Ffmpeg.encodeLossless(file);
        File input = optimized != null && !optimized.equals(file) ? optimized : file;

        checkDimensions(input);
        BufferedImage image;
        try {
            image = ImageIO.read(input);
        } catch (IOException e) {
            image = null;
            Logger.debug(e, "ImageIO failed to read {}", input.getName());
        }

        if (image == null) {
            image = Ffmpeg.decode(input);
        }

        if (image == null) {
            throw new IOException("No reader available for the file " + file.getName());
        }

        return image;
    }
}