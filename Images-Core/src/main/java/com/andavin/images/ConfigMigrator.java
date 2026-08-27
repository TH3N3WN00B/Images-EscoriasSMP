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
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Migrates older {@code config.yml} files to the version bundled with the
 * current plugin without losing values that the server administrator has
 * modified. A backup of the previous file is always written out as
 * {@code config.yml.bak} before anything is changed.
 *
 * @since November 13, 2023
 * @author Andavin
 */
public final class ConfigMigrator {

    private ConfigMigrator() {
    }

    /**
     * Migrate the given config file to the version bundled with the plugin
     * if it is outdated.
     *
     * @param configFile The config file to migrate.
     * @param bundledResource The bundled {@code config.yml} resource stream.
     * @return If the config file was updated (modified on disk).
     */
    public static boolean migrate(File configFile, InputStream bundledResource) {

        if (configFile == null || !configFile.isFile() || bundledResource == null) {
            return false;
        }

        try {

            YamlConfiguration current = new YamlConfiguration();
            current.load(configFile);
            int currentVersion = current.getInt("config-version", 0); // No version key = v0
            if (current.isSet("config-version") && !current.isInt("config-version")) {
                Logger.warn("config.yml has an invalid 'config-version'; treating it as 0 and migrating");
                currentVersion = 0;
            }

            // Read the whole bundled resource so that it can be written out
            // verbatim (keeping the comments) when there is nothing to migrate
            String bundledText;
            try (InputStream in = bundledResource) {
                ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    byteStream.write(buffer, 0, count);
                }
                bundledText = new String(byteStream.toByteArray(), StandardCharsets.UTF_8);
            }

            YamlConfiguration bundled = YamlConfiguration.loadConfiguration(new StringReader(bundledText));
            int bundledVersion = bundled.getInt("config-version", currentVersion);
            if (currentVersion >= bundledVersion) {
                return false; // Already up to date
            }

            // Carry over everything that differs from the newest defaults
            // (i.e. the values the administrator has modified). Only leaf
            // values are compared; section containers are always skipped.
            List<String> preserved = new ArrayList<>();
            YamlConfiguration merged = new YamlConfiguration();
            for (String key : bundled.getKeys(true)) {

                Object value = bundled.get(key);
                if (value instanceof ConfigurationSection) {
                    continue; // Not a leaf, the children are handled separately
                }

                merged.set(key, value);
                if (current.isSet(key)) {

                    Object currentValue = current.get(key);
                    if (currentValue != null && !currentValue.equals(value)) {
                        merged.set(key, currentValue);
                        preserved.add(key);
                    }
                }
            }

            merged.set("config-version", bundledVersion);
            File backup = new File(configFile.getAbsolutePath() + ".bak");
            Files.copy(configFile.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);

            if (preserved.isEmpty()) {
                // Nothing custom: write the new default file verbatim (keeps comments)
                Files.write(configFile.toPath(), bundledText.getBytes(StandardCharsets.UTF_8));
                Logger.info("Migrated config.yml to version {} (no custom values found)", bundledVersion);
            } else {
                merged.save(configFile);
                Logger.info("Migrated config.yml to version {}; preserved custom values: {}", bundledVersion, preserved);
            }

            return true;
        } catch (Exception e) {
            Logger.warn(e, "Unable to migrate config.yml; using the default configuration for this version");
            return false;
        }
    }
}