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

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import java.util.Locale;

/**
 * Removes grain and noise from an image before it is converted to the
 * map palette. Noise is what makes map data expensive: every section is
 * sent as 16 KB of color indexes and noisy ones barely compress, while
 * flat regions shrink to a few bytes. A median filter removes the noise
 * but keeps edges sharp, so the image looks practically the same on a
 * map (which only has a few dozen colors) but is far smaller to send.
 *
 * @since October 10, 2026
 * @author Andavin
 */
public final class ImageSmoother {

    /**
     * The strength of the smoothing.
     */
    public enum Level {

        OFF(0), LIGHT(1), STRONG(2);

        private final int radius;

        Level(int radius) {
            this.radius = radius;
        }

        /**
         * Parse a level from the configuration.
         *
         * @param name The name of the level (case insensitive).
         * @return The level or {@link #OFF} if it is unknown or empty.
         */
        public static Level parse(String name) {

            if (name != null) {
                try {
                    return valueOf(name.trim().toUpperCase(Locale.ENGLISH));
                } catch (IllegalArgumentException ignored) {
                }
            }

            return OFF;
        }
    }

    private ImageSmoother() {
    }

    /**
     * Apply a per channel median filter to the given image. Transparent
     * pixels (and their neighbors that are transparent) are not mixed
     * into opaque ones so edges of cut-out images stay clean.
     *
     * @param image The image to smooth.
     * @param level The strength of the smoothing.
     * @return A new smoothed image or the same image if the level is {@link Level#OFF}.
     */
    public static BufferedImage smooth(BufferedImage image, Level level) {

        if (level == null || level == Level.OFF) {
            return image;
        }

        int width = image.getWidth(), height = image.getHeight();
        int radius = level.radius;
        // Read straight from the image when possible and write straight into
        // the output, so no more than one extra pixel array is ever allocated
        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] result = ((DataBufferInt) output.getRaster().getDataBuffer()).getData();
        int[] source = pixelsOf(image);
        int capacity = (radius * 2 + 1) * (radius * 2 + 1);
        int[] reds = new int[capacity], greens = new int[capacity], blues = new int[capacity];
        for (int y = 0; y < height; y++) {

            for (int x = 0; x < width; x++) {

                int center = source[y * width + x];
                if ((center >>> 24) < 128) {
                    result[y * width + x] = center; // Transparent, leave as is
                    continue;
                }

                int count = 0;
                int minY = Math.max(0, y - radius), maxY = Math.min(height - 1, y + radius);
                int minX = Math.max(0, x - radius), maxX = Math.min(width - 1, x + radius);
                for (int ny = minY; ny <= maxY; ny++) {

                    for (int nx = minX; nx <= maxX; nx++) {

                        int pixel = source[ny * width + nx];
                        if ((pixel >>> 24) >= 128) {
                            reds[count] = (pixel >>> 16) & 0xFF;
                            greens[count] = (pixel >>> 8) & 0xFF;
                            blues[count] = pixel & 0xFF;
                            count++;
                        }
                    }
                }

                Arrays.sort(reds, 0, count);
                Arrays.sort(greens, 0, count);
                Arrays.sort(blues, 0, count);
                int mid = count / 2;
                result[y * width + x] = (center & 0xFF000000) |
                        (reds[mid] << 16) | (greens[mid] << 8) | blues[mid];
            }
        }

        return output;
    }

    private static int[] pixelsOf(BufferedImage image) {

        int width = image.getWidth(), height = image.getHeight();
        if (image.getType() == BufferedImage.TYPE_INT_ARGB &&
                image.getRaster().getDataBuffer() instanceof DataBufferInt &&
                image.getRaster().getSampleModelTranslateX() == 0 &&
                image.getRaster().getSampleModelTranslateY() == 0) {

            int[] data = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
            if (data.length == width * height) {
                return data;
            }
        }

        return image.getRGB(0, 0, width, height, null, 0, width);
    }
}
