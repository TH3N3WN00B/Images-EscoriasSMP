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
import javax.imageio.ImageIO;

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

    private ImageDecoder() {
    }

    /**
     * Decode the given image file into a {@link BufferedImage}.
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

        BufferedImage image;
        try {
            image = ImageIO.read(file);
        } catch (IOException e) {
            image = null;
            Logger.debug(e, "ImageIO failed to read {}", file.getName());
        }

        if (image == null) {
            image = Ffmpeg.decode(file);
        }

        if (image == null) {
            throw new IOException("No reader available for the file " + file.getName());
        }

        return image;
    }
}