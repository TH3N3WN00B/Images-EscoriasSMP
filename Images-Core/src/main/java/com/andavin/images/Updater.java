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
package com.andavin.images;

import com.andavin.util.Logger;
import com.andavin.util.Scheduler;

import org.bukkit.Bukkit;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Check for a newer release on GitHub and automatically
 * download it so that it can replace the running plugin
 * the next time the server shuts down.
 *
 * @since August 27, 2026
 * @author Andavin
 */
public final class Updater {

    private static final String REPOSITORY = "TH3N3WN00B/Images-EscoriasSMP";
    private static final String LATEST_URL = "https://api.github.com/repos/" + REPOSITORY + "/releases/latest";
    private static final Pattern TAG = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ASSET = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+\\.jar)\"");
    private static final Pattern DIGEST = Pattern.compile("\"digest\"\\s*:\\s*\"sha256:([0-9a-fA-F]{64})\"");
    private static final Pattern SAFE_VERSION = Pattern.compile("[0-9A-Za-z._+-]{1,40}");
    private static final String DOWNLOAD_PREFIX = "https://github.com/" + REPOSITORY + "/releases/download/";
    private static final Pattern NUMERIC = Pattern.compile("\\d+");
    private static final Pattern MINECRAFT = Pattern.compile("\\d+\\.\\d+(\\.\\d+)?");

    private static volatile Path downloaded;

    private Updater() {
    }

    /**
     * Start checking for updates. The first check happens shortly
     * after the server starts and repeats every {@code intervalMinutes}
     * after that.
     *
     * @param intervalMinutes How often to check for updates.
     */
    public static void start(int intervalMinutes) {
        long period = Math.max(1, intervalMinutes) * 60L * 20L;
        Scheduler.repeatAsync(Updater::check, 10L, period);
    }

    /**
     * Replace the running plugin jar with the downloaded update if one
     * was found. This should be called when the plugin is disabled so
     * that the new version is loaded the next time the server starts.
     *
     * @param pluginFile The file the running plugin was loaded from.
     */
    public static void apply(File pluginFile) {

        if (downloaded == null || !Files.exists(downloaded)) {
            return;
        }

        try {
            Files.move(downloaded, pluginFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Logger.info("Applied update, the new version will be loaded on next restart.");
        } catch (Exception e) {
            Logger.severe(e, "Failed to apply the downloaded update");
        } finally {
            downloaded = null;
        }
    }

    /**
     * Query the GitHub releases API for the latest release and, if it
     * is newer than the running version, download its {@code .jar}
     * asset so that {@link #apply(File)} can install it on shutdown.
     */
    public static void check() {

        try {
            String current = Images.getInstance().getDescription().getVersion();
            Latest latest = fetchLatest();
            if (latest == null) {
                return;
            }

            int comparison = compareVersions(latest.version, current);
            if (comparison <= 0) {
                Logger.debug("Plugin is up to date (v{}).", current);
                return;
            }

            Logger.info("A new version is available: v{} (current v{}).", latest.version, current);
            if (downloaded != null && Files.exists(downloaded)) {
                Logger.info("Update already downloaded and will be applied on next restart.");
                return;
            }

            if (!SAFE_VERSION.matcher(latest.version).matches()) {
                Logger.warn("Ignoring update with an invalid version '{}'", latest.version);
                return;
            }

            int index = selectAsset(latest.urls, minecraftVersion());
            if (index == -1) {
                Logger.debug("No update jar available for Minecraft {}", minecraftVersion());
                return;
            }

            String digest = latest.digests.get(index);
            if (digest == null) {
                // Never install a jar that cannot be verified
                Logger.warn("The update v{} has no SHA-256 digest; it was not downloaded", latest.version);
                return;
            }

            download(latest.version, latest.urls.get(index), digest);
        } catch (Exception e) {
            Logger.warn(e, "Failed to check for updates");
        }
    }

    /**
     * Fetch the latest release information from the GitHub API.
     *
     * @return The latest release or {@code null} if it could not be read.
     * @throws IOException If the request or response could not be handled.
     */
    private static Latest fetchLatest() throws IOException {

        HttpURLConnection connection = (HttpURLConnection) new URL(LATEST_URL).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(10_000);
        connection.setRequestProperty("User-Agent", "Images/" +
                Images.getInstance().getDescription().getVersion());
        connection.setRequestProperty("Accept", "application/vnd.github+json");

        try {
            int code = connection.getResponseCode();
            if (code != 200) {
                Logger.debug("Update check responded with HTTP {}", code);
                return null;
            }

            try (InputStream in = connection.getInputStream()) {
                String body = readAll(in);
                Matcher tag = TAG.matcher(body);
                Matcher asset = ASSET.matcher(body);
                if (!tag.find()) {
                    return null;
                }

                // Each asset is paired with the SHA-256 digest that GitHub
                // reports for it (found between the previous asset and it)
                List<String> urls = new ArrayList<>(4);
                List<String> digests = new ArrayList<>(4);
                int previous = 0;
                while (asset.find()) {

                    String url = asset.group(1);
                    String digest = null;
                    Matcher matcher = DIGEST.matcher(body.substring(previous, asset.start()));
                    while (matcher.find()) {
                        digest = matcher.group(1).toLowerCase(Locale.ENGLISH);
                    }

                    previous = asset.end();
                    if (url.startsWith(DOWNLOAD_PREFIX)) {
                        urls.add(url);
                        digests.add(digest);
                    }
                }

                return new Latest(tag.group(1), urls, digests);
            }
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Pick the jar asset that was built for the given Minecraft version.
     * If no match is found then no update is installed.
     *
     * @param urls All of the jar asset URLs from the release.
     * @param minecraft The Minecraft version the server is running.
     * @return The index of the asset to download or {@code -1} if there is none.
     */
    private static int selectAsset(List<String> urls, String minecraft) {

        // Only an asset built for this exact Minecraft version is used. Falling
        // back to an arbitrary jar could break the plugin on this server.
        if (minecraft != null) {

            String prefix = "images-escoriassmp-" + minecraft.toLowerCase(Locale.ENGLISH) + "-";
            for (int i = 0; i < urls.size(); i++) {
                if (urls.get(i).contains(prefix)) {
                    return i;
                }
            }
        }

        return -1;
    }

    /**
     * Get the Minecraft version that the server is currently running.
     *
     * @return The Minecraft version or {@code null} if it could not be determined.
     */
    private static String minecraftVersion() {

        Matcher matcher = MINECRAFT.matcher(Bukkit.getBukkitVersion());
        return matcher.find() ? matcher.group() : null;
    }

    /**
     * Download the given release jar into the plugin data folder and
     * remember it so that {@link #apply(File)} can install it on shutdown.
     *
     * @param version The version being downloaded.
     * @param url The URL of the jar to download.
     * @throws IOException If the download could not be completed.
     */
    private static void download(String version, String url, String digest) throws IOException {

        Path file = Images.getInstance().getDataFolder().toPath()
                .resolve("update-" + version + ".jar");
        Path partial = file.resolveSibling(file.getFileName() + ".part");
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(15_000);
        connection.setInstanceFollowRedirects(true);

        try {

            MessageDigest sha;
            try {
                sha = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IOException(e);
            }

            try (InputStream in = connection.getInputStream();
                 OutputStream out = Files.newOutputStream(partial)) {

                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    sha.update(buffer, 0, count);
                    out.write(buffer, 0, count);
                }
            }

            StringBuilder actual = new StringBuilder();
            for (byte b : sha.digest()) {
                actual.append(String.format("%02x", b));
            }

            if (!actual.toString().equals(digest)) {
                Files.deleteIfExists(partial);
                throw new IOException("Checksum mismatch for v" + version + ", the update was discarded");
            }

            Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING);
            downloaded = file;
            Logger.info("Downloaded v{} (verified), it will be applied on the next server restart.", version);
        } finally {
            connection.disconnect();
            Files.deleteIfExists(partial);
        }
    }

    /**
     * Compare two version strings honouring numeric segments.
     * A leading {@code v} is ignored.
     *
     * @param a The first version.
     * @param b The second version.
     * @return A negative int if {@code a < b}, zero if equal
     *         and a positive int if {@code a > b}.
     */
    private static int compareVersions(String a, String b) {

        String[] as = a.replaceFirst("^[vV]", "").split("[.\\-+]");
        String[] bs = b.replaceFirst("^[vV]", "").split("[.\\-+]");
        int length = Math.max(as.length, bs.length);
        for (int i = 0; i < length; i++) {
            int an = numeric(as, i);
            int bn = numeric(bs, i);
            if (an != bn) {
                return Integer.compare(an, bn);
            }
        }

        return 0;
    }

    private static int numeric(String[] parts, int index) {

        if (index >= parts.length) {
            return 0;
        }

        Matcher matcher = NUMERIC.matcher(parts[index]);
        return matcher.find() ? Integer.parseInt(matcher.group()) : 0;
    }

    /**
     * Read the entire contents of the given stream into a string.
     *
     * @param in The stream to read.
     * @return The contents of the stream.
     * @throws IOException If the stream could not be read.
     */
    private static String readAll(InputStream in) throws IOException {

        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }

        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static final class Latest {

        private final String version;
        private final List<String> urls;
        private final List<String> digests;

        private Latest(String version, List<String> urls, List<String> digests) {
            this.version = version;
            this.urls = urls;
            this.digests = digests;
        }
    }
}