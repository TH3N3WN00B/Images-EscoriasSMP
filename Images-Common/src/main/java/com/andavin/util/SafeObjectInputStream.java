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
package com.andavin.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.ObjectStreamClass;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * An {@link ObjectInputStream} that only resolves the small set of
 * classes that are used by the stored image format. Anything else is
 * rejected so that tampered data in a shared database (or data file)
 * cannot be used to instantiate arbitrary classes while deserializing.
 *
 * @since October 9, 2026
 * @author Andavin
 */
public final class SafeObjectInputStream extends ObjectInputStream {

    private static final Set<String> ALLOWED = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "com.andavin.images.image.CustomImage",
            "com.andavin.images.image.CustomImageSection",
            "org.bukkit.block.BlockFace",
            "java.util.HashMap",
            "java.util.UUID",
            "java.lang.Enum",
            "java.lang.Number",
            "java.lang.Integer",
            "java.lang.String",
            "[B"
    )));

    public SafeObjectInputStream(InputStream in) throws IOException {
        super(in);
    }

    @Override
    protected Class<?> resolveClass(ObjectStreamClass desc) throws IOException, ClassNotFoundException {

        String name = desc.getName();
        if (!ALLOWED.contains(name)) {
            throw new InvalidClassException(name, "class is not allowed in stored image data");
        }

        return super.resolveClass(desc);
    }
}
