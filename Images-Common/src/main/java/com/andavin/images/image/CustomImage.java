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
import com.andavin.util.LocationUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Collections.emptySet;

/**
 * @since September 20, 2019
 * @author Andavin
 */
public class CustomImage implements Serializable {

    private static final long serialVersionUID = 152608317193555652L;

    public static final UUID UNKNOWN_CREATOR = new UUID(
            -7650016060676093479L, -6847602434101430799L);
    private static final int PIXELS_PER_FRAME = 128;

    private transient int id = -1;
    private transient Location location;
    // Cached derived state. It is rebuilt lazily and never serialized
    private transient volatile CustomImageSection[] cache;
    private transient volatile Bounds bounds;
    private transient volatile Set<UUID> viewers;

    private final UUID creator;
    private final String imageName;
    private final BlockFace direction;
    private final Map<Integer, CustomImageSection> sections = new HashMap<>();

    public CustomImage(String imageName, Location location, BlockFace direction, BufferedImage image) {
        this(UNKNOWN_CREATOR, imageName, location, direction, image);
    }

    public CustomImage(UUID creator, String imageName, Location location,
                       BlockFace direction, BufferedImage image) {
        this.imageName = imageName;
        this.direction = direction;
        this.location = location;
        this.creator = creator;
        this.update(image);
    }

    /**
     * Get the ID of this image that is used for
     * a database.
     *
     * @return The database ID.
     */
    public int getId() {
        return id;
    }

    /**
     * Set the ID of this image that is used for
     * the database.
     *
     * @param id The ID to set to.
     */
    public void setId(int id) {
        this.id = id;
    }

    /**
     * Get the unique ID of the player who created
     * this image.
     *
     * @return The image creator or {@link #UNKNOWN_CREATOR} if
     *         there is no creator.
     */
    public UUID getCreator() {
        return creator;
    }

    /**
     * Get the name of the image file.
     *
     * @return The image name.
     */
    public String getImageName() {
        return imageName;
    }

    /**
     * Get the direction in which this image is facing.
     *
     * @return The direction.
     */
    public BlockFace getDirection() {
        return direction;
    }

    /**
     * Get the {@link CustomImageSection} that has the
     * entity item frame ID in this image.
     *
     * @param frameId The frame ID to get the section for.
     * @return The section or {@code null} if the
     *         section is not found.
     */
    public CustomImageSection getSection(int frameId) {
        synchronized (this.sections) {
            return this.sections.get(frameId);
        }
    }

    /**
     * Get the {@link CustomImageSection} that has the
     * map ID for this image.
     *
     * @param mapId The map ID to get the section for.
     * @return The section or {@code null} if the
     *         section is not found.
     */
    public CustomImageSection getSectionByMap(int mapId) {

        for (CustomImageSection section : this.snapshot()) {

            if (section.getMapId() == mapId) {
                return section;
            }
        }

        return null;
    }

    /**
     * Show or hide this image for a player if they are within
     * 64 blocks or outside of 128 blocks of any of its sections.
     *
     * @param player The player to show the image to.
     * @param location The location of the player to measure the distance from.
     */
    public void refresh(Player player, Location location) {

        CustomImageSection[] all = this.snapshot();
        if (all.length == 0) {
            return;
        }

        UUID id = player.getUniqueId();
        Set<UUID> viewers = this.viewers();
        boolean viewer = viewers.contains(id);
        if (!viewer) {
            // Nothing has been shown to this player so nothing can be hidden. If
            // the whole image is out of showing range there is nothing to do
            // either, which is by far the most common case on a busy server
            if (MapHelper.isCulling()) {
                // Showing is decided by showIfVisible(), which only shows what is looked at
                return;
            }

            Bounds bounds = this.bounds(all);
            if (location == null || !bounds.world.equals(location.getWorld())) {
                return;
            }

            double distance = bounds.distanceSquared(location);
            if (distance > MapHelper.showDistance * MapHelper.showDistance) {
                return;
            }
        }

        for (CustomImageSection section : all) {

            boolean sameWorld = location != null && section.getLocation().getWorld().equals(location.getWorld());
            if (sameWorld) {

                double distance = section.getLocation().distanceSquared(location);
                if (distance <= MapHelper.showDistance * MapHelper.showDistance) {
                    // With culling an image is only extended once it has been seen
                    if (viewer || !MapHelper.isCulling()) {
                        section.show(player);
                    }
                } else if (distance > MapHelper.hideDistance * MapHelper.hideDistance) {
                    section.hide(player);
                }
            } else {
                section.hide(player);
            }
        }

        boolean any = false;
        for (CustomImageSection section : all) {
            if (section.isShownTo(id)) {
                any = true;
                break;
            }
        }

        if (any) {
            viewers.add(id);
        } else if (viewer) {
            viewers.remove(id);
        }
    }

    /**
     * Show this image to the player if they can see it right now. It is
     * only shown when it is in showing range, the player is in front of it,
     * it is within the given angle of where they look and (optionally) the
     * line of sight is not blocked by blocks.
     * <p>
     * Once shown the image stays until it is out of the hiding range, which
     * is handled by {@link #refresh(Player, Location)}. This must be called
     * from the main thread when {@code occlusion} is used.
     *
     * @param player The player to show the image to.
     * @param eye The location of the eyes of the player (with the direction).
     * @param cosLimit The cosine of the largest angle from the view direction.
     * @param occlusion If blocks that are in the way should hide the image.
     * @param budget A single element array with the amount of line of sight
     *               tests that are still allowed, decreased for each test.
     * @return If the image is now being shown to the player.
     */
    public boolean showIfVisible(Player player, Location eye, double cosLimit, boolean occlusion, int[] budget) {

        CustomImageSection[] all = this.snapshot();
        if (all.length == 0) {
            return false;
        }

        UUID id = player.getUniqueId();
        Set<UUID> viewers = this.viewers();
        if (viewers.contains(id)) {
            return true;
        }

        Bounds bounds = this.bounds(all);
        if (!bounds.world.equals(eye.getWorld())) {
            return false;
        }

        double show = MapHelper.showDistance;
        if (bounds.distanceSquared(eye) > show * show || !bounds.isVisible(eye, cosLimit, occlusion, budget)) {
            return false;
        }

        boolean shown = false;
        for (CustomImageSection section : all) {

            if (section.getLocation().distanceSquared(eye) <= show * show) {
                section.show(player);
                shown = true;
            }
        }

        if (shown) {
            viewers.add(id);
        }

        return shown;
    }

    /**
     * Remove a player from this image so that it can be
     * refreshed for them in the future.
     *
     * @param player The player to remove from this image.
     * @param online If the player is online and the images
     *               should be hidden from them.
     */
    public void remove(Player player, boolean online) {

        if (!this.viewers().remove(player.getUniqueId()) && !online) {
            // Never shown to this player, so there is nothing to forget
            return;
        }

        for (CustomImageSection section : this.snapshot()) {

            if (online) {
                section.hide(player);
            } else {
                section.forget(player.getUniqueId());
            }
        }
    }

    /**
     * Destroy all of the sections of this image and hide it
     * from all players that it has been shown to if they are
     * currently online.
     *
     * @return The players that the image was hidden from.
     */
    public Set<Player> destroy() {

        CustomImageSection[] all;
        synchronized (this.sections) {
            all = this.sections.values().toArray(new CustomImageSection[0]);
            this.sections.clear();
            this.cache = null;
            this.bounds = null;
        }

        this.viewers().clear();

        int sections = all.length;
        if (sections != 0) {

            int index = 0;
            int[] ids = new int[sections];
            Set<Player> players = new HashSet<>(sections);
            for (CustomImageSection section : all) {
                ids[index++] = section.getFrameId();
                for (UUID viewer : section.viewers()) {
                    Player player = Bukkit.getPlayer(viewer);
                    if (player != null) {
                        players.add(player);
                    }
                }

                section.unregister();
            }

            for (Player player : players) {
                MapHelper.destroyMaps(player, ids);
            }

            return players;
        }

        return emptySet();
    }

    /**
     * Update this image to the new given {@link BufferedImage}.
     *
     * @param image The image to update to.
     */
    private void update(BufferedImage image) {

        int rotation;
        BlockFace face;
        Set<Player> players = this.destroy();
        switch (direction) {
            case UP:
                face = LocationUtil.getCardinalDirection(location).getOppositeFace();
                rotation = (int) (LocationUtil.getDifference(BlockFace.SOUTH, face) / 90);
                break;
            case DOWN:
                face = LocationUtil.getCardinalDirection(location).getOppositeFace();
                rotation = (int) (LocationUtil.getDifference(face, BlockFace.SOUTH) / 90);
                break;
            case NORTH:
            case SOUTH:
            case EAST:
            case WEST:
                face = BlockFace.SELF;
                rotation = 0;
                break;
            default:
                throw new IllegalStateException("Invalid direction " + direction);
        }

        int xSections = Math.max(image.getWidth() / PIXELS_PER_FRAME, 1);
        int ySections = Math.max(image.getHeight() / PIXELS_PER_FRAME, 1);
        image = resize(image, xSections, ySections);
        for (int x = 0; x < xSections; x++) {

            for (int y = 0; y < ySections; y++) {

                Location loc = location.clone();
                switch (direction) {
                    case UP:

                        switch (face) {
                            case NORTH:
                                loc.add(-x, 0, -y);
                                break;
                            case SOUTH:
                                loc.add(x, 0, y);
                                break;
                            case EAST:
                                loc.add(y, 0, -x);
                                break;
                            case WEST:
                                loc.add(-y, 0, x);
                                break;
                        }

                        break;
                    case DOWN:

                        switch (face) {
                            case NORTH:
                                loc.add(-x, 0, y);
                                break;
                            case SOUTH:
                                loc.add(x, 0, -y);
                                break;
                            case EAST:
                                loc.add(-y, 0, -x);
                                break;
                            case WEST:
                                loc.add(y, 0, x);
                                break;
                        }

                        break;
                    case NORTH:
                        loc.add(-x, -y, 0);
                        break;
                    case SOUTH:
                        loc.add(x, -y, 0);
                        break;
                    case EAST:
                        loc.add(0, -y, -x);
                        break;
                    case WEST:
                        loc.add(0, -y, x);
                        break;
                }

                CustomImageSection section = new CustomImageSection(loc, direction,
                        rotation, image.getSubimage(x * PIXELS_PER_FRAME, y * PIXELS_PER_FRAME,
                        PIXELS_PER_FRAME, PIXELS_PER_FRAME));
                synchronized (this.sections) {
                    this.sections.put(section.getFrameId(), section);
                    this.cache = null;
                    this.bounds = null;
                }
            }
        }

        this.dedupePixels();

        for (Player player : players) {
            this.refresh(player, player.getLocation());
        }
    }

    /**
     * Tell if this image or any of its sections
     * are within the specified range of a {@link Location}.
     *
     * @param location The location to test within range of.
     * @param range The range to test with.
     * @return If the location is in range of this image.
     */
    public boolean isInRange(Location location, int range) {

        if (!this.location.getWorld().equals(location.getWorld())) {
            return false;
        }

        int rangeSqrd = range * range;
        if (this.location.distanceSquared(location) <= rangeSqrd) {
            return true;
        }

        for (CustomImageSection section : this.snapshot()) {

            if (section.getLocation().distanceSquared(location) <= rangeSqrd) {
                return true;
            }
        }

        return false;
    }

    private CustomImageSection[] snapshot() {

        CustomImageSection[] array = this.cache;
        if (array == null) {

            synchronized (this.sections) {
                array = this.cache;
                if (array == null) {
                    this.cache = array = this.sections.values().toArray(new CustomImageSection[0]);
                }
            }
        }

        return array;
    }

    private Set<UUID> viewers() {

        Set<UUID> set = this.viewers;
        if (set == null) {

            synchronized (this.sections) {
                set = this.viewers;
                if (set == null) {
                    this.viewers = set = ConcurrentHashMap.newKeySet(2);
                }
            }
        }

        return set;
    }

    private Bounds bounds(CustomImageSection[] all) {

        Bounds bounds = this.bounds;
        if (bounds == null) {
            this.bounds = bounds = new Bounds(all, this.direction);
        }

        return bounds;
    }

    /**
     * Make this image findable by the frame and map IDs of its sections.
     * This must be called once the image is added for use and is undone
     * by {@link #destroy()}.
     */
    public void register() {

        for (CustomImageSection section : this.snapshot()) {
            section.register(this);
        }
    }

    /**
     * Make sections that are exactly the same share one pixel array.
     * Flat backgrounds and repeated areas then only use memory (and
     * serialized space) once, and blank sections use the shared array.
     */
    private void dedupePixels() {

        Map<Integer, java.util.List<byte[]>> seen = new HashMap<>();
        for (CustomImageSection section : this.snapshot()) {

            byte[] pixels = section.rawPixels();
            java.util.List<byte[]> candidates = seen.computeIfAbsent(Arrays.hashCode(pixels), k -> new ArrayList<>(1));
            byte[] match = null;
            for (byte[] candidate : candidates) {
                if (candidate == pixels || Arrays.equals(candidate, pixels)) {
                    match = candidate;
                    break;
                }
            }

            if (match == null) {
                candidates.add(pixels);
            } else if (match != pixels) {
                section.intern(match);
            }
        }
    }

    /**
     * The area that all of the sections of an image are contained in.
     */
    private static final class Bounds {

        private final World world;
        private final double minX, minY, minZ, maxX, maxY, maxZ;
        // The direction the image faces (the normal of its plane)
        private final double normalX, normalY, normalZ;
        // Points of the face of the image that are tested for being visible
        private final double[][] samples;

        private Bounds(CustomImageSection[] all, BlockFace face) {

            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
            for (CustomImageSection section : all) {

                Location loc = section.getLocation();
                minX = Math.min(minX, loc.getX());
                minY = Math.min(minY, loc.getY());
                minZ = Math.min(minZ, loc.getZ());
                maxX = Math.max(maxX, loc.getX());
                maxY = Math.max(maxY, loc.getY());
                maxZ = Math.max(maxZ, loc.getZ());
            }

            this.world = all[0].getLocation().getWorld();
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
            this.normalX = face.getModX();
            this.normalY = face.getModY();
            this.normalZ = face.getModZ();

            // The center and the corners of the face, in the middle of the
            // block and just in front of it. The center comes first since
            // it is the most likely to be seen
            double offset = 0.45;
            double[] xs = {(minX + maxX) / 2, minX, maxX};
            double[] ys = {(minY + maxY) / 2, minY, maxY};
            double[] zs = {(minZ + maxZ) / 2, minZ, maxZ};
            java.util.List<double[]> points = new ArrayList<>(9);
            for (double sx : xs) {
                for (double sy : ys) {
                    for (double sz : zs) {

                        double[] point = {Math.floor(sx) + 0.5 + this.normalX * offset,
                                Math.floor(sy) + 0.5 + this.normalY * offset, Math.floor(sz) + 0.5 + this.normalZ * offset};
                        boolean duplicate = false;
                        for (double[] other : points) {
                            if (other[0] == point[0] && other[1] == point[1] && other[2] == point[2]) {
                                duplicate = true;
                                break;
                            }
                        }

                        if (!duplicate) {
                            points.add(point);
                        }
                    }
                }
            }

            this.samples = points.toArray(new double[0][]);
        }

        /**
         * Test if any point of the image can be seen from the eye location.
         */
        private boolean isVisible(Location eye, double cosLimit, boolean occlusion, int[] budget) {

            double ex = eye.getX(), ey = eye.getY(), ez = eye.getZ();
            Vector look = eye.getDirection();
            for (double[] point : this.samples) {

                double vx = point[0] - ex, vy = point[1] - ey, vz = point[2] - ez;
                // The map can only be seen from the side that it faces
                if (this.normalX * vx + this.normalY * vy + this.normalZ * vz >= 0) {
                    continue;
                }

                double distance = Math.sqrt(vx * vx + vy * vy + vz * vz);
                // Very close points are always counted, an angle is not meaningful there
                if (distance > 2.5 &&
                        (vx * look.getX() + vy * look.getY() + vz * look.getZ()) / distance < cosLimit) {
                    continue;
                }

                if (!occlusion) {
                    return true;
                }

                if (budget[0] <= 0) {
                    return false;
                }

                budget[0]--;
                if (!LineOfSight.isBlocked(this.world, eye, point[0], point[1], point[2])) {
                    return true;
                }
            }

            return false;
        }

        /**
         * Get the squared distance from the location to the closest point
         * of the box. Every section is at least this far away.
         */
        private double distanceSquared(Location loc) {

            double dx = Math.max(Math.max(this.minX - loc.getX(), 0), loc.getX() - this.maxX);
            double dy = Math.max(Math.max(this.minY - loc.getY(), 0), loc.getY() - this.maxY);
            double dz = Math.max(Math.max(this.minZ - loc.getZ(), 0), loc.getZ() - this.maxZ);
            return dx * dx + dy * dy + dz * dz;
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.direction, this.imageName, this.creator, this.location);
    }

    @Override
    public boolean equals(Object obj) {

        if (obj == this) {
            return true;
        }

        if (!(obj instanceof CustomImage)) {
            return false;
        }

        CustomImage image = (CustomImage) obj;
        return this.direction == image.direction &&
                this.imageName.equals(image.imageName) &&
                this.creator.equals(image.creator) &&
                this.location.equals(image.location);
    }

    private void writeObject(ObjectOutputStream out) throws IOException {
        out.defaultWriteObject();
        writeLocation(out, this.location);
    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {

        in.defaultReadObject();
        this.location = readLocation(in);
        CustomImageSection[] sections = this.sections.values().toArray(new CustomImageSection[0]);
        this.sections.clear();
        for (CustomImageSection section : sections) {
            this.sections.put(section.getFrameId(), section);
        }

        this.dedupePixels();
    }

    static void writeLocation(ObjectOutputStream out, Location location) throws IOException {
        out.writeObject(location.getWorld().getName());
        out.writeInt(location.getBlockX());
        out.writeInt(location.getBlockY());
        out.writeInt(location.getBlockZ());
        out.writeFloat(location.getYaw());
        out.writeFloat(location.getPitch());
    }

    static Location readLocation(ObjectInputStream in) throws IOException, ClassNotFoundException {

        String name = (String) in.readObject();
        World world = Bukkit.getWorld(name);
        checkState(world != null, "unknown world with ID %s", name);
        return new Location(
                world,
                in.readInt(), in.readInt(), in.readInt(),
                in.readFloat(), in.readFloat()
        );
    }

    private static BufferedImage resize(BufferedImage image, int xSections, int ySections) {

        if (image.getWidth() % PIXELS_PER_FRAME == 0 && image.getHeight() % PIXELS_PER_FRAME == 0) {
            return image;
        }
        // Get a scaled version of the image
        return ImageScaler.scale(image, xSections * PIXELS_PER_FRAME, ySections * PIXELS_PER_FRAME);
    }
}
