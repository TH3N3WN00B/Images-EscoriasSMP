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
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;

/**
 * Simple wrapper around a static {@code ffmpeg} binary that is used to
 * decode formats that the Java ImageIO cannot read by itself (JPEG XL,
 * WebM) as well as a fallback for anything else.
 * <p>
 * The binary is downloaded exactly once per platform (unless a custom
 * path is given in the config) from the BtbN FFmpeg-Builds releases
 * (which include JPEG XL and image/video hardware encoders) and cached
 * in the plugin's data directory under {@code .ffmpeg/}. The download
 * happens lazily on the first use so startup is never blocked. The
 * capability set of the binary (encoders, hardware acceleration) and
 * the availability of actual hardware devices (i.e. in Docker) are
 * probed in the background and used to pick the per-format command
 * as well as whether hardware acceleration can be attempted, always
 * falling back to plain software decoding.
 *
 * @since November 13, 2023
 * @author Andavin
 */
public final class Ffmpeg {

    /**
     * The base URL used to download static ffmpeg binaries.
     * The token {@code {filename}} is replaced with the
     * platform specific archive name.
     */
    public static final String DEFAULT_DOWNLOAD_URL =
            "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/{filename}";
    private static final long EXEC_TIMEOUT_SECONDS = 60L;
    private static final int PROCESS_FAILED = Integer.MIN_VALUE;
    private static final int PROCESS_TIMEOUT = PROCESS_FAILED + 1;
    private static final Set<String> RASTER_LOSSLESS = new HashSet<>(
            Arrays.asList("png", "bmp", "tga", "wbmp"));
    private static final String[][] LOSSLESS_CODECS = {
            {"jxl", "-c:v", "libjxl", "-distance", "0", "-effort", "3"},
            {"webp", "-c:v", "libwebp", "-lossless", "1", "-compression_level", "6", "-quality", "100"},
            {"png", "-c:v", "png", "-compression_level", "9"},
            {"jpg", "-c:v", "mjpeg", "-q:v", "2"}
    };
    private static final String[] PREFER_ORDER = {"JXL", "WEBP", "PNG"};
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static File baseDir = new File(".");
    private static boolean enabled = true;
    private static boolean recompress = true;
    private static String preferredFormat = "AUTO";
    private static String overridePath = "";
    private static String downloadUrl = DEFAULT_DOWNLOAD_URL;
    private static boolean resolved; // If the binary has already been found/attempted
    private static File binary;      // The resolved binary or null if not usable
    private static boolean probed;   // If the capability set has already been attempted
    private static Capabilities capabilities;
    private static File cacheDir;

    private Ffmpeg() {
    }

    /**
     * Configure ffmpeg with the settings from the config file.
     *
     * @param dataFolder The data folder of the plugin to download the binary into.
     * @param enabled Whether ffmpeg decoding should be enabled at all.
     * @param pathOverride The path to a custom ffmpeg binary or empty to auto-download.
     * @param downloadUrl The base download URL or null to use the default.
     * @param recompress Whether image sources should be re-compressed losslessly when read.
     * @param preferredFormat The preferred re-compression format ({@code AUTO}, {@code PNG},
     *                        {@code WEBP} or {@code JXL}) or null/empty for automatic.
     */
    public static void configure(File dataFolder, boolean enabled, String pathOverride,
                                 String downloadUrl, boolean recompress, String preferredFormat) {

        Ffmpeg.baseDir = dataFolder != null ? dataFolder : baseDir;
        Ffmpeg.enabled = enabled;
        Ffmpeg.overridePath = pathOverride != null ? pathOverride.trim() : "";
        if (downloadUrl != null && !downloadUrl.trim().isEmpty()) {
            Ffmpeg.downloadUrl = downloadUrl.trim();
        }
        Ffmpeg.recompress = recompress;
        if (preferredFormat != null && !preferredFormat.trim().isEmpty()) {
            Ffmpeg.preferredFormat = preferredFormat.trim().toUpperCase(Locale.ENGLISH);
        }
        cacheDir = null;

        binary = null;
        resolved = false;
        probed = false;
        capabilities = null;
        if (!enabled) {
            resolved = true;
            Logger.info("ffmpeg image decoding is disabled (config).");
            return;
        }
        if (!overridePath.isEmpty()) {
            Logger.info("ffmpeg decoding enabled with custom binary '{}'", overridePath);
        } else {
            Logger.info("ffmpeg decoding enabled (downloads a static binary on first use)");
        }

        // Probe the binary in the background so that the capabilities
        // (encoders, hardware acceleration) are known without blocking
        // startup and can be reported to the console.
        Thread probe = new Thread(() -> {
            try {
                Capabilities caps = capabilities();
                if (caps != null) {
                    Logger.info("ffmpeg capabilities: enc(libjxl={}, libwebp={}, vp9={}, mjpeg={}), hw-accels={}, decode-hw={}",
                            caps.libJxl, caps.libWebp, caps.vp9, caps.mjpeg, caps.hwaccels,
                            caps.hwAccel == null ? "none (software)" : caps.hwAccel);
                }
            } catch (Throwable e) {
                Logger.debug(e, "Could not probe ffmpeg capabilities");
            }
        }, "images-ffmpeg-probe");
        probe.setDaemon(true);
        probe.start();
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
     * <p>
     * When decoding a video ({@code maxFrames > 1}) and a hardware
     * acceleration method is available for this platform (and the actual
     * device is mounted, i.e. not hidden in a Docker container) it will
     * be attempted first and fall back to plain software decoding if it
     * fails. Still images never use hardware acceleration.
     *
     * @param file The file to decode.
     * @param maxFrames The maximum amount of frames to decode.
     * @return The decoded frames or an empty list if ffmpeg is unavailable
     *         or the file could not be decoded.
     */
    public static List<BufferedImage> decodeFrames(File file, int maxFrames) {

        Capabilities caps = capabilities();
        if (maxFrames > 1 && caps != null && caps.hwAccel != null) {
            // Try hardware acceleration first for videos, software otherwise
            List<BufferedImage> frames = decodeFrames(file, maxFrames, caps.hwAccel);
            if (!frames.isEmpty()) {
                return frames;
            }
            Logger.debug("ffmpeg hardware decoding failed for {}; used software instead", file.getName());
        }

        return decodeFrames(file, maxFrames, (String) null);
    }

    /**
     * Losslessly re-compress the given source file with the command
     * appropriate for its format and cache the result (keyed by content)
     * in the {@code .imgcache} folder. The source is returned unchanged
     * if ffmpeg is unavailable, re-compression is disabled, the format
     * has no suitable encoder or the compressed result would be larger.
     * <p>
     * The per-format command is chosen from the probed capabilities:
     * raster-lossless formats (PNG, BMP, TGA, WBMP), WebP and JPEG XL
     * are re-encoded to the smallest available lossless format (JPEG XL
     * {@code -distance 0}, WebP {@code -lossless 1} or optimized PNG)
     * while JPEG is re-encoded near-losslessly with MJPEG ({@code -q:v 2}).
     *
     * @param source The source file to re-compress.
     * @return The cached re-compressed file or the source itself if it
     *         could not be improved.
     */
    public static File encodeLossless(File source) {

        if (!recompress || source == null || !source.isFile()) {
            return source;
        }

        Capabilities caps = capabilities();
        if (caps == null) {
            return source;
        }

        String ext = extension(source.getName());
        String codec;
        if (RASTER_LOSSLESS.contains(ext) || ext.equals("webp") || ext.equals("jxl")) {
            codec = pickLosslessCodec(caps);
        } else if ((ext.equals("jpg") || ext.equals("jpeg")) && caps.mjpeg) {
            codec = "jpg"; // JPEG cannot be losslessly re-encoded
        } else {
            return source; // Video, GIF or any other unimprovable format
        }
        if (codec == null) {
            return source; // No suitable encoder available
        }

        try {

            File dir = cacheDir();
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return source;
            }

            File cached = new File(dir, hashHex(source) + "." + codec);
            if (cached.isFile() && cached.length() > 0) {
                return cached;
            }

            if (!encode(source, cached, codecArgs(codec))) {
                return source; // Failed; the partial target is removed by encode
            }

            // The compressed version is only worth keeping if it is smaller
            if (cached.isFile() && cached.length() > 0 && cached.length() < source.length()) {
                Logger.debug("ffmpeg re-compressed {} ({}) to {} ({})",
                        source.getName(), source.length(), cached.getName(), cached.length());
                return cached;
            }

            cached.delete();
        } catch (Exception e) {
            Logger.debug(e, "Unable to losslessly re-compress {} with ffmpeg", source.getName());
        }

        return source;
    }

    /**
     * Pick the smallest available lossless format, honoring the
     * configured preferred format ({@code JXL}/{@code WEBP}/{@code PNG})
     * whenever the matching encoder is present.
     *
     * @param caps The probed capabilities.
     * @return The lossless codec (e.g. {@code jxl}) or {@code null}.
     */
    private static String pickLosslessCodec(Capabilities caps) {

        for (String preferred : PREFER_ORDER) {
            if (preferred.equals(preferredFormat) && available(preferred.toLowerCase(Locale.ENGLISH), caps)) {
                return preferred.toLowerCase(Locale.ENGLISH);
            }
        }
        for (String candidate : PREFER_ORDER) {
            if (available(candidate.toLowerCase(Locale.ENGLISH), caps)) {
                return candidate.toLowerCase(Locale.ENGLISH);
            }
        }
        return null;
    }

    private static boolean available(String codec, Capabilities caps) {
        switch (codec) {
            case "jxl":
                return caps.libJxl;
            case "webp":
                return caps.libWebp;
            case "png":
                return caps.png;
            default:
                return false;
        }
    }

    /**
     * The encoder arguments for the given codec (first element of a
     * {@link #LOSSLESS_CODECS} row) or {@code null}.
     */
    private static String[] codecArgs(String codec) {
        for (String[] row : LOSSLESS_CODECS) {
            if (row[0].equals(codec)) {
                return row;
            }
        }
        return null;
    }

    /**
     * Actually run the decode process with an optional hardware
     * acceleration method and collect the frames as PNG files in a
     * temporary directory.
     *
     * @param file The file to decode.
     * @param maxFrames The maximum amount of frames to decode.
     * @param hwAccel The hardware acceleration method or {@code null} for software.
     * @return The decoded frames or empty if it failed.
     */
    private static List<BufferedImage> decodeFrames(File file, int maxFrames, String hwAccel) {

        File exe = install();
        if (exe == null) {
            return new ArrayList<>();
        }

        String pattern = "frame-%03d.png";
        File frameDir = new File(baseDir, ".ffmpeg" + File.separator + "tmp" + File.separator + Long.toHexString(System.nanoTime()));
        boolean success = false;
        try {

            if (!frameDir.isDirectory() && !frameDir.mkdirs()) {
                throw new IOException("unable to create directory " + frameDir);
            }

            List<String> command = new ArrayList<>();
            command.add(exe.getAbsolutePath());
            command.add("-hide_banner");
            command.add("-loglevel");
            command.add("error");
            command.add("-y");
            if (hwAccel != null) {
                command.add("-hwaccel");
                command.add(hwAccel);
            }
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
            command.add("image2");
            command.add(new File(frameDir, pattern).getAbsolutePath());

            success = run(command, null, "decoding " + file.getName()) == 0;
        } catch (IOException e) {
            Logger.debug(e, "Unable to decode {} with ffmpeg", file.getName());
        }

        List<BufferedImage> frames = new ArrayList<>(Math.min(maxFrames, 4));
        if (success) {
            try {
                int index = 1;
                while (frames.size() < maxFrames) {
                    File frame = new File(frameDir, String.format(Locale.ENGLISH, pattern, index++));
                    if (!frame.isFile()) {
                        break;
                    }
                    BufferedImage image = ImageIO.read(frame); // File reader always works
                    if (image == null) {
                        break;
                    }
                    frames.add(image);
                }
            } catch (Exception e) {
                Logger.debug(e, "Unable to read decoded frames for {}", file.getName());
            }
        }

        deleteDir(frameDir);
        return frames;
    }

    /**
     * Run a full encode of {@code source} to {@code target} with the
     * given codec arguments and wait for it to finish. Any partial
     * output file is removed so a failed encode never leaves a broken
     * file behind.
     *
     * @param source The source file.
     * @param target The output file (overwritten if it exists).
     * @param codecArgs The codec and its arguments (e.g. {@code -c:v libjxl ...}).
     * @return If the encode succeeded.
     */
    private static boolean encode(File source, File target, String[] codecArgs) {

        File exe = install();
        if (exe == null) {
            return false;
        }

        target.delete();
        List<String> command = new ArrayList<>();
        command.add(exe.getAbsolutePath());
        command.add("-hide_banner");
        command.add("-loglevel");
        command.add("error");
        command.add("-y");
        command.add("-i");
        command.add(source.getAbsolutePath());
        command.add("-an");
        command.add("-sn");
        // The row's first element is the codec label; the rest is the encoder args
        command.addAll(Arrays.asList(codecArgs).subList(1, codecArgs.length));
        command.add(target.getAbsolutePath());

        boolean ok = run(command, null, "compressing " + source.getName()) == 0
                && target.isFile() && target.length() > 0;
        if (!ok) {
            target.delete();
        }
        return ok;
    }

    /**
     * Run the given command to completion, draining the output streams
     * so the process never blocks. Combined output is discarded unless
     * {@code capture} is given (used for the capability probes).
     *
     * @param command The command to run.
     * @param capture The buffer to capture combined output into or {@code null}.
     * @param tag A short description for log messages.
     * @return The process exit code, {@link #PROCESS_TIMEOUT} on timeout or
     *         {@link #PROCESS_FAILED} if it could not be run.
     */
    private static int run(List<String> command, ByteArrayOutputStream capture, String tag) {

        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            Thread drain = drain(process.getInputStream(), capture);
            if (!process.waitFor(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                Logger.debug("ffmpeg timed out while {}", tag);
                drain.join(1000);
                return PROCESS_TIMEOUT;
            }
            drain.join(1000);
            return process.exitValue();
        } catch (Exception e) {
            Logger.debug(e, "ffmpeg failed while {}", tag);
            return PROCESS_FAILED;
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }
    }

    private static Thread drain(InputStream in, ByteArrayOutputStream capture) {

        Thread thread = new Thread(() -> {
            try {
                byte[] buffer = new byte[16384];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    if (capture != null) {
                        capture.write(buffer, 0, count);
                    }
                }
            } catch (IOException ignored) {
            }
        }, "images-ffmpeg-drain");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * Resolve the ffmpeg binary, downloading and extracting it for the
     * current platform if there is no cached copy.
     *
     * @return The binary or {@code null} if it is unavailable.
     */
    private static synchronized File install() {

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
            Logger.warn("No static ffmpeg binary is available for platform {} {}; " +
                            "set a custom 'ffmpeg.path' in the config",
                    System.getProperty("os.name"), System.getProperty("os.arch"));
            return null;
        }

        String binName = os.equals("win32") ? "ffmpeg.exe" : "ffmpeg";
        String assembly;
        if (os.equals("win32") && arch.equals("x64")) {
            assembly = "ffmpeg-master-latest-win64-gpl.zip";
        } else if (os.equals("win32") && arch.equals("arm64")) {
            assembly = "ffmpeg-master-latest-winarm64-gpl.zip";
        } else if (os.equals("linux") && arch.equals("x64")) {
            assembly = "ffmpeg-master-latest-linux64-gpl.tar.xz";
        } else if (os.equals("linux") && arch.equals("arm64")) {
            assembly = "ffmpeg-master-latest-linuxarm64-gpl.tar.xz";
        } else {
            Logger.warn("No static ffmpeg binary is available for platform {} {}; " +
                            "set a custom 'ffmpeg.path' in the config",
                    System.getProperty("os.name"), System.getProperty("os.arch"));
            return null;
        }

        File dir = new File(baseDir, ".ffmpeg");
        File target = new File(dir, binName);
        if (target.isFile() && target.length() > 0) {
            binary = target;
            Logger.info("ffmpeg: using cached binary '{}'", target);
            return binary;
        }

        try {

            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("unable to create directory " + dir);
            }

            URL url = new URL(downloadUrl.replace("{filename}", assembly));
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

            File archive = new File(dir, assembly);
            try (InputStream in = connection.getInputStream();
                 OutputStream out = new FileOutputStream(archive)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    out.write(buffer, 0, count);
                }
            } finally {
                connection.disconnect();
            }

            File temp = new File(dir, binName + ".tmp");
            if (assembly.endsWith(".zip")) {
                extractZip(archive, temp, binName);
            } else {
                extractTarXz(archive, temp, binName);
            }

            archive.delete(); // Not needed once extracted
            target.delete();
            if (!temp.renameTo(target)) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            if (!target.setExecutable(true, false)) {
                Logger.debug("Could not set the executable bit on {}", target);
            }
            // Make sure the downloaded binary actually runs before accepting it
            if (probe(target, "-version").isEmpty()) {
                throw new IOException("downloaded binary does not execute");
            }

            binary = target;
            Logger.info("ffmpeg static binary installed to '{}'", target);
        } catch (Exception e) {
            Logger.severe(e, "Unable to download static ffmpeg binary");
            target.delete();
            binary = null;
        }

        return binary;
    }

    /**
     * Extract the {@code bin/ffmpeg[.exe]} entry from a zip archive
     * (Windows builds) to the given target file.
     */
    private static void extractZip(File archive, File target, String binName) throws IOException {

        try (ZipInputStream zip = new ZipInputStream(new FileInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().contains("/bin/") && entry.getName().endsWith("/" + binName) && !entry.isDirectory()) {
                    copyTo(zip, target);
                    return;
                }
            }
        }
        throw new IOException("no " + binName + " found in " + archive.getName());
    }

    /**
     * Extract the {@code bin/ffmpeg} entry from a {@code tar.xz} archive
     * (Linux builds) to the given target file.
     */
    private static void extractTarXz(File archive, File target, String binName) throws IOException {

        try (TarArchiveInputStream tar = new TarArchiveInputStream(
                new XZCompressorInputStream(new FileInputStream(archive)))) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
                String name = entry.getName();
                if (entry.isFile() && name.contains("/bin/") && name.endsWith("/" + binName)) {
                    copyTo(tar, target);
                    return;
                }
            }
        }
        throw new IOException("no " + binName + " found in " + archive.getName());
    }

    private static void copyTo(InputStream in, File target) throws IOException {
        try (OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = in.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }
        }
    }

    /**
     * The folder used to cache losslessly re-compressed image sources.
     */
    private static File cacheDir() {
        if (cacheDir == null) {
            cacheDir = new File(baseDir, ".imgcache");
        }
        return cacheDir;
    }

    private static void deleteDir(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                file.delete();
            }
        }
        dir.delete();
    }

    /**
     * The capabilities (encoders, hardware acceleration) of the binary,
     * probed once in the background. {@code null} until the binary has
     * been probed or if it is unavailable.
     */
    private static synchronized Capabilities capabilities() {

        if (probed) {
            return capabilities;
        }

        probed = true;
        File exe = install();
        if (exe == null) {
            return null;
        }

        Set<String> hwaccels = new HashSet<>();
        try {
            String hwText = probe(exe, "-hwaccels");
            for (String method : new String[]{"vaapi", "cuda", "dxva2", "d3d11va", "d3d12va", "qsv", "amf"}) {
                if (hwText.contains("\n" + method + "\n")) {
                    hwaccels.add(method);
                }
            }
        } catch (Exception ignore) {
            // Probed values are best-effort; an empty set means no hardware
        }

        try {
            String encoders = probe(exe, "-encoders");
            capabilities = new Capabilities(hwaccels,
                    encoders.contains("libjxl"),
                    encoders.contains("libwebp"),
                    encoders.contains("libvpx-vp9"),
                    encoders.contains("mjpeg "), // Plain mjpeg (not mjpeg_qsv/vaapi)
                    encoders.contains("png "),
                    chooseHwAccel(hwaccels));
        } catch (Exception e) {
            Logger.debug(e, "Unable to probe ffmpeg capabilities");
            probed = false; // Retry later
            capabilities = null;
        }

        return capabilities;
    }

    /**
     * Run ffmpeg with a single extra argument and capture the combined
     * output, normalizing line endings so it can be parsed on any platform.
     *
     * @param exe The binary.
     * @param extraArg The extra argument (e.g. {@code -encoders}).
     * @return The combined output or empty if the process failed.
     */
    private static String probe(File exe, String extraArg) {

        List<String> command = new ArrayList<>(
                Arrays.asList(exe.getAbsolutePath(), "-hide_banner", extraArg));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (run(command, out, "probing " + extraArg) != 0) {
            return "";
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .replace('\r', '\n');
    }

    /**
     * Decide which (if any) hardware acceleration method is actually
     * usable on this machine. Docker containers only count when the
     * devices are actually mounted, otherwise software is used.
     */
    private static String chooseHwAccel(Set<String> hwaccels) {

        if (hwaccels.isEmpty()) {
            return null;
        }

        String os = osName();
        if (os != null && os.equals("linux")) {
            boolean vaapiDevice = !list("/dev/dri", "render*", "card*").isEmpty();
            boolean nvidiaDevice = !list("/dev", "nvidia*").isEmpty();
            if (inDocker() && !vaapiDevice && !nvidiaDevice) {
                Logger.debug("Running in Docker without GPU devices mounted; using software decoding");
                return null;
            }
            if (vaapiDevice && hwaccels.contains("vaapi")) {
                return "vaapi";
            }
            if (nvidiaDevice && hwaccels.contains("cuda")) {
                return "cuda";
            }
            return null;
        }

        if (os != null && os.equals("win32")) {
            for (String method : new String[]{"d3d11va", "dxva2", "cuda"}) {
                if (hwaccels.contains(method)) {
                    return method;
                }
            }
        }

        return null;
    }

    private static boolean inDocker() {

        if (new File("/.dockerenv").exists()) {
            return true;
        }
        try {
            String content = new String(Files.readAllBytes(new File("/proc/self/cgroup").toPath()), StandardCharsets.UTF_8);
            return content.contains("docker") || content.contains("kubepods");
        } catch (IOException e) {
            return false;
        }
    }

    private static List<File> list(String dirName, String... globs) {

        List<File> matches = new ArrayList<>();
        File dir = new File(dirName);
        String[] files = dir.list();
        if (files == null) {
            return matches;
        }
        for (String name : files) {
            for (String glob : globs) {
                if (name.startsWith(glob.replace("*", ""))) {
                    // Only prefix globs (render*, card*, nvidia*) are used
                    matches.add(new File(dir, name));
                    break;
                }
            }
        }
        return matches;
    }

    private static String hashHex(File file) throws Exception {

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = in.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        byte[] bytes = digest.digest();
        char[] hex = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xFF;
            hex[i * 2] = HEX[value >>> 4];
            hex[i * 2 + 1] = HEX[value & 0x0F];
        }
        return new String(hex);
    }

    private static String extension(String name) {

        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ENGLISH);
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

    /**
     * The probed capability set of the ffmpeg binary.
     */
    private static final class Capabilities {

        private final Set<String> hwaccels;
        private final boolean libJxl;
        private final boolean libWebp;
        private final boolean vp9;
        private final boolean mjpeg;
        private final boolean png;
        private final String hwAccel;

        private Capabilities(Set<String> hwaccels, boolean libJxl, boolean libWebp,
                             boolean vp9, boolean mjpeg, boolean png, String hwAccel) {
            this.hwaccels = hwaccels;
            this.libJxl = libJxl;
            this.libWebp = libWebp;
            this.vp9 = vp9;
            this.mjpeg = mjpeg;
            this.png = png;
            this.hwAccel = hwAccel;
        }
    }
}