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

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Resizes images directly on {@link BufferedImage BufferedImages}.
 * <p>
 * This replaces {@code Image#getScaledInstance} which is very slow and
 * allocates an extra image (and its producer/consumer machinery) for
 * every resize. Large images are shrunk in steps of two with bilinear
 * interpolation, which is what gives good quality (the same averaging
 * that a smooth scale does) at a fraction of the time.
 *
 * @since October 11, 2026
 * @author Andavin
 */
public final class ImageScaler {

    private ImageScaler() {
    }

    /**
     * Resize the given image.
     *
     * @param image The image to resize.
     * @param width The new width.
     * @param height The new height.
     * @return A new {@link BufferedImage#TYPE_INT_ARGB} image of the given size.
     */
    public static BufferedImage scale(BufferedImage image, int width, int height) {

        int currentWidth = image.getWidth(), currentHeight = image.getHeight();
        BufferedImage current = image;
        while (currentWidth > width || currentHeight > height) {

            int nextWidth = currentWidth > width ? Math.max(width, currentWidth / 2) : currentWidth;
            int nextHeight = currentHeight > height ? Math.max(height, currentHeight / 2) : currentHeight;
            current = step(current, nextWidth, nextHeight);
            currentWidth = nextWidth;
            currentHeight = nextHeight;
        }

        if (currentWidth != width || currentHeight != height || current == image) {
            current = step(current, width, height);
        }

        return current;
    }

    private static BufferedImage step(BufferedImage source, int width, int height) {

        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }

        return target;
    }
}
