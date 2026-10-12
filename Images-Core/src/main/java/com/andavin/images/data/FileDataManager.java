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
package com.andavin.images.data;

import com.andavin.images.image.CustomImage;
import com.andavin.reflect.exception.UncheckedClassNotFoundException;
import com.andavin.util.SafeObjectInputStream;
import com.andavin.util.Zstd;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * @since September 21, 2019
 * @author Andavin
 */
public class FileDataManager implements DataManager {

    private final File dataFile;
    private List<CustomImage> cache; // The images as of the last read or write

    public FileDataManager(File dataFile) {
        checkArgument(!dataFile.exists() || dataFile.isFile(),
                "not a file %s", dataFile);
        this.dataFile = dataFile;
    }

    @Override
    public void initialize() {
    }

    @Override
    public synchronized List<CustomImage> load() {

        List<CustomImage> images = this.read();
        this.cache = new ArrayList<>(images);
        return images;
    }

    @Override
    public synchronized void save(CustomImage image) {
        // Write everything again, but from the images that are already in
        // memory instead of reading and deserializing the whole file first
        List<CustomImage> images = new ArrayList<>(this.cached());
        images.add(image);
        this.saveAll(images);
    }

    @Override
    public synchronized void saveAll(List<CustomImage> images) {

        // An empty list must still be written, otherwise deleting the
        // last image would leave it in the file and it would come back
        File temp = new File(this.dataFile.getAbsolutePath() + ".tmp");
        try {

            try (OutputStream fileOut = new FileOutputStream(temp);
                 OutputStream out = Zstd.wrap(fileOut);
                 ObjectOutputStream stream = new ObjectOutputStream(out)) {

                stream.writeInt(images.size());
                for (CustomImage image : images) {
                    stream.writeObject(image);
                    // Forget what was written so the stream does not hold a
                    // reference to every image until it is closed
                    stream.reset();
                }
            }

            // Only move once every stream has been flushed and closed
            Files.move(temp.toPath(), this.dataFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            this.cache = new ArrayList<>(images);
        } catch (IOException e) {
            temp.delete();
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized void delete(CustomImage image) {

        List<CustomImage> images = new ArrayList<>(this.cached());
        if (images.remove(image)) {
            this.saveAll(images);
        }
    }

    private List<CustomImage> cached() {

        if (this.cache == null) {
            this.cache = new ArrayList<>(this.read());
        }

        return this.cache;
    }

    /**
     * Read all of the images from the file. The file is streamed through
     * the decompressor so that neither the compressed nor the decompressed
     * data are ever held in memory as a whole.
     */
    private List<CustomImage> read() {

        if (!this.dataFile.isFile() || this.dataFile.length() == 0) {
            return new ArrayList<>();
        }

        try (InputStream fileIn = new BufferedInputStream(new FileInputStream(this.dataFile), 65536);
             ObjectInputStream stream = new SafeObjectInputStream(Zstd.unwrap(fileIn))) {

            int capacity = stream.readInt();
            if (capacity < 0) {
                throw new StreamCorruptedException("negative image count " + capacity);
            }

            // Never trust the stored count for the allocation
            List<CustomImage> images = new ArrayList<>(Math.min(capacity, 4096));
            for (int i = 0; i < capacity; i++) {
                images.add((CustomImage) stream.readObject());
            }

            return images;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (ClassNotFoundException e) {
            throw new UncheckedClassNotFoundException(e.getMessage(), e);
        }
    }
}
