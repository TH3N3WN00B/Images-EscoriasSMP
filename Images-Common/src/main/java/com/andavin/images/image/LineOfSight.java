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

import org.bukkit.Location;
import org.bukkit.World;

/**
 * Tests if the view between two points is blocked by solid blocks.
 * <p>
 * This walks the voxels along the line (so it works on every Minecraft
 * version) and only reads chunks that are already loaded. It reads
 * blocks and so must only be called from the main server thread.
 *
 * @since October 11, 2026
 * @author Andavin
 */
final class LineOfSight {

    private LineOfSight() {
    }

    /**
     * Test if any occluding block is between the eye and the target.
     * The block of the eye and the block of the target are never counted.
     *
     * @param world The world to test in.
     * @param eye The start of the line.
     * @param tx The X coordinate of the target.
     * @param ty The Y coordinate of the target.
     * @param tz The Z coordinate of the target.
     * @return If the view is blocked.
     */
    static boolean isBlocked(World world, Location eye, double tx, double ty, double tz) {

        double sx = eye.getX(), sy = eye.getY(), sz = eye.getZ();
        double dx = tx - sx, dy = ty - sy, dz = tz - sz;
        int x = floor(sx), y = floor(sy), z = floor(sz);
        int endX = floor(tx), endY = floor(ty), endZ = floor(tz);
        int stepX = dx > 0 ? 1 : -1, stepY = dy > 0 ? 1 : -1, stepZ = dz > 0 ? 1 : -1;
        double deltaX = dx == 0 ? Double.MAX_VALUE : Math.abs(1 / dx);
        double deltaY = dy == 0 ? Double.MAX_VALUE : Math.abs(1 / dy);
        double deltaZ = dz == 0 ? Double.MAX_VALUE : Math.abs(1 / dz);
        double maxX = dx == 0 ? Double.MAX_VALUE : (stepX > 0 ? x + 1 - sx : sx - x) * deltaX;
        double maxY = dy == 0 ? Double.MAX_VALUE : (stepY > 0 ? y + 1 - sy : sy - y) * deltaY;
        double maxZ = dz == 0 ? Double.MAX_VALUE : (stepZ > 0 ? z + 1 - sz : sz - z) * deltaZ;
        int limit = Math.abs(endX - x) + Math.abs(endY - y) + Math.abs(endZ - z) + 2;
        for (int i = 0; i < limit; i++) {

            if (maxX < maxY) {
                if (maxX < maxZ) {
                    x += stepX;
                    maxX += deltaX;
                } else {
                    z += stepZ;
                    maxZ += deltaZ;
                }
            } else if (maxY < maxZ) {
                y += stepY;
                maxY += deltaY;
            } else {
                z += stepZ;
                maxZ += deltaZ;
            }

            if (x == endX && y == endY && z == endZ) {
                return false; // Reached the target
            }

            // Never load a chunk just to look through it
            if (world.isChunkLoaded(x >> 4, z >> 4) && world.getBlockAt(x, y, z).getType().isOccluding()) {
                return true;
            }
        }

        return false;
    }

    private static int floor(double value) {
        int i = (int) value;
        return value < i ? i - 1 : i;
    }
}
