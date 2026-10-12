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

import com.andavin.images.MapHelper;
import com.andavin.util.MinecraftVersion;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static com.andavin.images.image.CustomImage.readLocation;
import static com.andavin.images.image.CustomImage.writeLocation;

/**
 * @since September 20, 2019
 * @author Andavin
 */
public class CustomImageSection implements Serializable {

    private static final long serialVersionUID = 572225588300845874L;

    /**
     * The starting ID of the item frames.
     */
    public static final int DEFAULT_STARTING_ID = Integer.MAX_VALUE / 4;
    private static final int PIXEL_COUNT = 128 * 128;
    private static final AtomicInteger ID_COUNTER = new AtomicInteger(DEFAULT_STARTING_ID);
    /**
     * A single shared array for every section that has no visible pixel
     * so that blank sections (borders, transparent areas) cost no memory.
     */
    private static final byte[] BLANK = new byte[PIXEL_COUNT];
    // Registry of the sections of every image that is in use. This makes
    // finding a section by its frame or map ID constant time instead of
    // scanning every section of every image for each interaction
    private static final Map<Integer, CustomImageSection> BY_FRAME = new ConcurrentHashMap<>();
    private static final Map<Integer, CustomImageSection> BY_MAP = new ConcurrentHashMap<>();

    private byte[] pixels;
    private final BlockFace direction;
    private final int rotation;

    private transient Location location;
    private transient int frameId, mapId; // Should not be
    // Allocated lazily since most sections are not shown to anyone at a given time
    private transient volatile Set<UUID> shown;
    private transient boolean empty;
    private transient volatile CustomImage image;

    CustomImageSection(Location location, BlockFace direction, int rotation, BufferedImage image) {
        this.rotation = rotation;
        this.frameId = ID_COUNTER.getAndIncrement();
        this.mapId = MapHelper.getNextMapId(location.getWorld());
        this.location = location;
        this.direction = direction;
        this.pixels = MapHelper.getPixels(image);
        this.empty = isBlank(this.pixels);
        if (this.empty) {
            this.pixels = BLANK;
        }
    }

    /**
     * Get the section that is in the item frame with the given entity ID.
     *
     * @param frameId The frame ID.
     * @return The section or {@code null} if there is none.
     */
    public static CustomImageSection getByFrame(int frameId) {
        return BY_FRAME.get(frameId);
    }

    /**
     * Get the section that is displayed on the map with the given ID.
     *
     * @param mapId The map ID.
     * @return The section or {@code null} if there is none.
     */
    public static CustomImageSection getByMap(int mapId) {
        return BY_MAP.get(mapId);
    }

    /**
     * Remove every section from the registry.
     */
    public static void clearRegistry() {
        BY_FRAME.clear();
        BY_MAP.clear();
    }

    void register(CustomImage owner) {
        this.image = owner;
        BY_FRAME.put(this.frameId, this);
        BY_MAP.putIfAbsent(this.mapId, this);
    }

    void unregister() {
        BY_FRAME.remove(this.frameId, this);
        BY_MAP.remove(this.mapId, this);
        this.image = null;
    }

    /**
     * Get the image that this section is a part of.
     *
     * @return The image or {@code null} if the image is not in use.
     */
    public CustomImage getImage() {
        return image;
    }

    /**
     * Get the ID of the item frame that this section
     * of the image is located in.
     *
     * @return The frame ID.
     */
    public int getFrameId() {
        return frameId;
    }

    /**
     * Get the ID of the map that is located within the
     * the item frame.
     *
     * @return The map ID.
     */
    public int getMapId() {
        return mapId;
    }

    /**
     * Get the {@link Location} of this item frame section.
     *
     * @return The frame location.
     */
    public Location getLocation() {
        return location;
    }

    /**
     * Get the direction that this image section is facing.
     *
     * @return The direction.
     */
    public BlockFace getDirection() {
        return direction;
    }

    /**
     * Get the amount of rotation that this section is within
     * its own space (0-8).
     *
     * @return The rotation.
     */
    public int getRotation() {
        return rotation;
    }

    /**
     * Get a copy of the pixel color array for
     * this image section.
     *
     * @return The pixels.
     */
    public byte[] getPixels() {
        return pixels.clone();
    }

    /**
     * Share the pixel array with an identical one that is already in use
     * so that equal sections use no extra memory.
     *
     * @param canonical The array to use (must have the same content).
     */
    void intern(byte[] canonical) {
        this.pixels = canonical;
    }

    byte[] rawPixels() {
        return this.pixels;
    }

    boolean isEmpty() {
        return this.empty;
    }

    /**
     * Test if this section has been shown to the given player.
     *
     * @param id The ID of the player.
     * @return If it has been shown.
     */
    boolean isShownTo(UUID id) {
        Set<UUID> set = this.shown;
        return set != null && set.contains(id);
    }

    /**
     * Forget the given player without sending anything to them.
     *
     * @param id The ID of the player.
     */
    void forget(UUID id) {
        Set<UUID> set = this.shown;
        if (set != null) {
            set.remove(id);
        }
    }

    /**
     * Get the IDs of all the players that this section is shown to.
     *
     * @return The IDs.
     */
    Collection<UUID> viewers() {
        Set<UUID> set = this.shown;
        return set == null ? Collections.<UUID>emptySet() : set;
    }

    private boolean markShown(UUID id) {

        Set<UUID> set = this.shown;
        if (set == null) {

            synchronized (this) {
                set = this.shown;
                if (set == null) {
                    this.shown = set = ConcurrentHashMap.newKeySet(2);
                }
            }
        }

        return set.add(id);
    }

    /**
     * Show this image section to the given player.
     *
     * @param player The player to show to.
     */
    public void show(Player player) {

        if (this.markShown(player.getUniqueId()) && !this.skip()) {
            MapHelper.createMap(player, this.frameId, this.mapId, this.location, this.direction, this.rotation, this.pixels);
        }
    }

    /**
     * Hide this image section from the given player.
     *
     * @param player The player to hide from.
     */
    public void hide(Player player) {

        Set<UUID> set = this.shown;
        if (set != null && set.remove(player.getUniqueId()) && !this.skip()) {
            MapHelper.destroyMaps(player, this.frameId);
        }
    }

    /**
     * A section with no visible pixel has nothing to show when the frames
     * are invisible (1.16+), so neither the frame nor the map is sent.
     */
    private boolean skip() {
        return this.empty && MapHelper.isInvisible() &&
                MinecraftVersion.greaterThanOrEqual(MinecraftVersion.v1_16);
    }

    static boolean isBlank(byte[] pixels) {

        for (byte pixel : pixels) {
            if (pixel != 0) {
                return false;
            }
        }

        return true;
    }

    private void writeObject(ObjectOutputStream out) throws IOException {
        out.defaultWriteObject();
        writeLocation(out, this.location);
    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        this.location = readLocation(in);
        this.frameId = ID_COUNTER.getAndIncrement();
        this.mapId = MapHelper.getNextMapId(location.getWorld());
        this.empty = isBlank(this.pixels);
        if (this.empty) {
            this.pixels = BLANK;
        }
    }
}
