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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;

/**
 * Simple wrapper around a static {@code ffmpeg} binary that is used to
 * decode formats that the Java ImageIO cannot read by itself (JPEG XL,
 * WebM) as well as a fallback for anything else.
 * <p>
 * The binary is downloaded exactly once per platform (unless a custom
 * path is given in the config) and cached in the plugin's data
 * directory under {@code .ffmpeg/}. The download happens lazily on the
 * first decode request so startup is never blocked.
 *
 * @since November 13, 2023
 * @author Andavin
 */
public final class Ffmpeg {

    /**
     * The base URL used to download static ffmpeg binaries.
     * The token {@code {filename}} is replaced with the
     * platform specific file name.
     */
    public static final String DEFAULT_DOWNLOAD_URL =
            "https://github.com/eugeneware/ffmpeg-static/releases/download/b6.0/{filename}";
    private static final long EXEC_TIMEOUT_SECONDS = 60L;

    private static File baseDir = new File(".");
    private static boolean enabled = true;
    private static String overridePath = "";
    private static String downloadUrl = DEFAULT_DOWNLOAD_URL;
    private static boolean resolved; // If the binary has already been found/attempted
    private static File binary;      // The resolved binary or null if not usable

    private Ffmpeg() {
    }

    /**
     * Configure ffmpeg with the settings from the config file.
     *
     * @param dataFolder The data folder of the plugin to download the binary into.
     * @param enabled Whether ffmpeg decoding should be enabled at all.
     * @param pathOverride The path to a custom ffmpeg binary or empty to auto-download.
     * @param downloadUrl The base download URL or null to use the default.
     */
    public static void configure(File dataFolder, boolean enabled, String pathOverride, String downloadUrl) {

        Ffmpeg.baseDir = dataFolder != null ? dataFolder : baseDir;
        Ffmpeg.enabled = enabled;
        Ffmpeg.overridePath = pathOverride != null ? pathOverride.trim() : "";
        if (downloadUrl != null && !downloadUrl.trim().isEmpty()) {
            Ffmpeg.downloadUrl = downloadUrl.trim();
        }

        binary = null;
        resolved = false;
        if (!enabled) {
            resolved = true;
            Logger.info("ffmpeg image decoding is disabled (config).");
        } else if (!Ffmpeg.overridePath.isEmpty()) {
            Logger.info("ffmpeg decoding enabled with custom binary '{}'", Ffmpeg.overridePath);
        } else {
            Logger.info("ffmpeg decoding enabled (downloads a static binary on first use)");
        }
    }

    /**
     * Test if the static ffmpeg binary has been installed for this platform.
     * This resolves (and potentially downloads) the binary.
     *
     * @return If the binary is available.
     */
    public static synchronized boolean isAvailable() {
        return install() != null;
    }

    /**
     * Decode the first frame (or the single image) from the
     * given file using ffmpeg.
     *
     * @param file The file to decode.
     * @return The first frame or {@code null} if the file
     *         could not be decoded or ffmpeg is unavailable.
     */
    public static BufferedImage decode(File file) {

        List<BufferedImage> frames = decodeFrames(file, 1);
        return frames.isEmpty() ? null : frames.get(0);
    }

    /**
     * Decode up to {@code maxFrames} frames from the given file using
     * ffmpeg. This is mainly useful for video files such as WebM where
     * each frame can be used as a single image.
     *
     * @param file The file to decode.
     * @param maxFrames The maximum amount of frames to decode.
     * @return The decoded frames or an empty list if ffmpeg is unavailable
     *         or the file could not be decoded.
     */
    public static List<BufferedImage> decodeFrames(File file, int maxFrames) {

        File exe = install();
        if (exe == null) {
            return new ArrayList<>();
        }

        List<BufferedImage> frames = new ArrayList<>(Math.min(maxFrames, 4));
        Process process = null;
        try {

            List<String> command = new ArrayList<>();
            command.add(exe.getAbsolutePath());
            command.add("-hide_banner");
            command.add("-loglevel");
            command.add("error");
            command.add("-y");
            command.add("-i");
            command.add(file.getAbsolutePath());
            command.add("-an");
            command.add("-sn");
            if (maxFrames == 1) {
                command.add("-frames:v");
                command.add("1");
            }
            command.add("-c:v");
            command.add("png");
            command.add("-f");
            command.add("image2pipe");
            command.add("-");

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(false);
            process = builder.start();
            // Drain the stderr stream so that it doesn't fill its buffer
            // and block ffmpeg from writing the frames to the stdout pipe
            final Process proc = process;
            Thread errorDrain = new Thread(() -> {
                try {
                    InputStream err = proc.getErrorStream();
                    while (err.read() != -1) {
                        // Do nothing
                    }
                } catch (IOException ignored) {
                }
            }, "images-ffmpeg-stderr");
            errorDrain.setDaemon(true);
            errorDrain.start();

            try (ImageInputStream input = ImageIO.createImageInputStream(process.getInputStream())) {

                while (frames.size() < maxFrames) {
                    BufferedImage image = ImageIO.read(input);
                    if (image == null) {
                        break;
                    }
                    frames.add(image);
                }
            }

            if (!process.waitFor(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("ffmpeg timed out");
            }
            if (process.exitValue() != 0) {
                Logger.debug("ffmpeg exited with code {} while decoding {}", process.exitValue(), file.getName());
                return new ArrayList<>();
            }
        } catch (Exception e) {
            Logger.debug(e, "Unable to decode {} with ffmpeg", file.getName());
            return new ArrayList<>();
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }

        return frames;
    }

    private static File install() {

        if (resolved) {
            return binary;
        }

        resolved = true;
        if (!enabled) {
            return null;
        }

        if (!overridePath.isEmpty()) {

            File custom = new File(overridePath);
            if (custom.isFile()) {
                binary = custom;
            } else {
                Logger.severe("Configured ffmpeg path '{}' does not exist. ffmpeg formats unavailable", overridePath);
                binary = null;
            }

            return binary;
        }

        String os = osName(), arch = archName();
        if (os == null || arch == null) {
            Logger.warn("No static ffmpeg binary is available for platform {} {}",
                    System.getProperty("os.name"), System.getProperty("os.arch"));
            return null;
        }

        String fileName = "ffmpeg-" + os + "-" + arch + (os.equals("win32") ? ".exe" : "");
        File target = new File(baseDir, ".ffmpeg" + File.separator + fileName);
        if (target.isFile() && target.length() > 0) {
            binary = target;
            Logger.info("ffmpeg: using cached binary '{}'", target);
            return binary;
        }

        try {

            File dir = target.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("unable to create directory " + dir);
            }

            URL url = new URL(downloadUrl.replace("{filename}", fileName));
            Logger.info("Downloading static ffmpeg binary from {} ...", url);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(60000);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 Images-EscoriasSMP");
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code + " while downloading " + url);
            }

            File temp = new File(baseDir, ".ffmpeg" + File.separator + fileName + ".tmp");
            try (InputStream in = connection.getInputStream();
                 OutputStream out = new FileOutputStream(temp)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    out.write(buffer, 0, count);
                }
            } finally {
                connection.disconnect();
            }

            target.delete();
            if (!temp.renameTo(target)) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            if (!target.setExecutable(true, false)) {
                Logger.debug("Could not set the executable bit on {}", target);
            }

            binary = target;
            Logger.info("ffmpeg static binary installed to '{}'", target);
        } catch (Exception e) {
            Logger.severe(e, "Unable to download static ffmpeg binary");
            binary = null;
        }

        return binary;
    }

    private static String osName() {

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ENGLISH);
        if (os.contains("win")) {
            return "win32";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "darwin";
        }
        if (os.contains("linux")) {
            return "linux";
        }

        return null;
    }

    private static String archName() {

        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ENGLISH);
        if (arch.contains("amd64") || arch.contains("x86_64")) {
            return "x64";
        }
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            return "arm64";
        }
        if (arch.contains("arm")) {
            return "arm";
        }
        if (arch.contains("86")) {
            return "ia32";
        }

        return null;
    }
}