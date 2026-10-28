/*
 * This file is part of ClassGraph.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/classgraph/classgraph
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2026 Luke Hutchison
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.github.classgraph.vfs.internal.module;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

import io.github.classgraph.base.LogNode;

/**
 * Helper methods for opening a {@link ModuleReference} and reading resources from a {@link ModuleReader},
 * converting {@link Optional}, {@link Stream}, {@link UncheckedIOException}, and {@link SecurityException} into
 * plain return values and {@link IOException}.
 */
public final class ModuleReaderUtils {
    /** Not instantiable. */
    private ModuleReaderUtils() {
        // Empty
    }

    /**
     * Unwrap an {@link Optional} returned by a {@link ModuleReader} method.
     *
     * @param <T>
     *            the value type.
     * @param optional
     *            the {@link Optional} returned by the method.
     * @param moduleReader
     *            the module reader, for error messages.
     * @param methodName
     *            the name of the method that was called, for error messages.
     * @param path
     *            the resource path passed to the method, for error messages.
     * @return the unwrapped value.
     * @throws IOException
     *             if {@code optional} is null or empty.
     */
    private static <T> T getValue(final @Nullable Optional<T> optional, final ModuleReader moduleReader,
            final String methodName, final String path) throws IOException {
        if (optional == null) {
            throw new IOException("ModuleReader#" + methodName + "(String) returned null for path " + path
                    + ", which its contract does not permit -- this is a bug in the ModuleReader implementation "
                    + moduleReader.getClass().getName());
        }
        return optional.orElseThrow(() -> new IOException(
                "ModuleReader#" + methodName + "(String) did not find a resource with path " + path));
    }

    /**
     * Open a {@link ModuleReference}, returning a {@link ModuleReader} for reading its contents.
     *
     * @param moduleReference
     *            the module to open.
     * @return a {@link ModuleReader} for the module.
     * @throws IOException
     *             if the module could not be opened.
     */
    public static ModuleReader openModule(final ModuleReference moduleReference) throws IOException {
        final ModuleReader moduleReader;
        try {
            moduleReader = moduleReference.open();
        } catch (final SecurityException e) {
            throw new IOException("Could not open module " + moduleReference.descriptor().name(), e);
        }
        if (moduleReader == null) {
            // ModuleReference#open() is specified to return a ModuleReader, and is not allowed to return null, so a
            // null return means the ModuleReference implementation does not honor its contract
            throw new IOException("ModuleReference#open() returned null for module "
                    + moduleReference.descriptor().name() + ", which its contract does not permit -- this is a bug "
                    + "in the ModuleReference implementation " + moduleReference.getClass().getName());
        }
        return moduleReader;
    }

    /**
     * List the resource paths in a {@link ModuleReader}.
     *
     * @param moduleReader
     *            the module reader.
     * @param moduleName
     *            the module name, for error messages.
     * @param log
     *            the log, or null for no logging.
     * @return a mutable list of resource paths in the module (or an empty list if {@link ModuleReader#list()}
     *         violated its contract by returning null).
     * @throws IOException
     *             if the module contents could not be listed.
     */
    public static List<String> list(final ModuleReader moduleReader, final String moduleName,
            final @Nullable LogNode log) throws IOException {
        final Stream<String> resourcesStream;
        try {
            resourcesStream = moduleReader.list();
        } catch (final IOException | SecurityException e) {
            throw new IOException("Could not call ModuleReader#list() for module " + moduleName, e);
        }
        if (resourcesStream == null) {
            // ModuleReader#list() is specified to return a Stream<String>, and is not allowed to return null, so a
            // null return means the ModuleReader implementation does not honor its contract. Some do anyway --
            // e.g. Minecraft Forge's securejarhandler (cpw.mods.cl.JarModuleFinder$JarModuleReader) -- so treat the
            // module as empty rather than aborting the whole scan, and record which implementation is at fault in
            // the log, so that the report can go to the right project. (#887)
            if (log != null) {
                log.log("ModuleReader#list() returned null for module " + moduleName
                        + ", which its contract does not permit -- this is a bug in the ModuleReader "
                        + "implementation " + moduleReader.getClass().getName()
                        + " -- treating the module as empty");
            }
            // Mutable, for the same reason as the list built below
            return new ArrayList<>();
        }
        // The stream may hold an open directory of an exploded module, and it is closing the stream that closes
        // the directory, so the stream has to be closed even though nothing is read from it afterwards
        try (resourcesStream) {
            // N.B. the returned list must be mutable, since ModuleRoot sorts it in place (so Stream#toList() cannot
            // be used here)
            return resourcesStream.collect(Collectors.toCollection(ArrayList::new));
        } catch (final UncheckedIOException e) {
            // A lazy ModuleReader reports a failure to read the module as an UncheckedIOException thrown from the
            // stream operation that caused the read, rather than from ModuleReader#list() itself, so unwrap it
            throw new IOException("Could not list the contents of module " + moduleName, e.getCause());
        } catch (final SecurityException e) {
            throw new IOException("Could not list the contents of module " + moduleName, e);
        }
    }

    /**
     * Open the named resource in a {@link ModuleReader} as an {@link InputStream}.
     *
     * @param moduleReader
     *            the module reader.
     * @param path
     *            the resource path to open.
     * @return an {@link InputStream} for the resource that wraps any {@link SecurityException} thrown during
     *         reading in an {@link IOException}.
     * @throws IOException
     *             if the resource could not be opened.
     */
    public static InputStream open(final ModuleReader moduleReader, final String path) throws IOException {
        final Optional<InputStream> optionalInputStream;
        try {
            optionalInputStream = moduleReader.open(path);
        } catch (final IOException | SecurityException e) {
            throw new IOException("Could not call ModuleReader#open(String) for path " + path, e);
        }
        return new SecurityExceptionWrappingInputStream(getValue(optionalInputStream, moduleReader, "open", path),
                path);
    }

    /**
     * A {@link FilterInputStream} that wraps any {@link SecurityException} thrown while reading in an
     * {@link IOException}.
     */
    private static final class SecurityExceptionWrappingInputStream extends FilterInputStream {
        /** The path of the resource, for error messages. */
        private final String path;

        /**
         * Constructor.
         *
         * @param inputStream
         *            the stream to wrap.
         * @param path
         *            the path of the resource, for error messages.
         */
        SecurityExceptionWrappingInputStream(final InputStream inputStream, final String path) {
            super(inputStream);
            this.path = path;
        }

        @Override
        public int read() throws IOException {
            try {
                return super.read();
            } catch (final SecurityException e) {
                throw new IOException("Could not read " + path, e);
            }
        }

        @Override
        public int read(final byte[] b, final int off, final int len) throws IOException {
            try {
                return super.read(b, off, len);
            } catch (final SecurityException e) {
                throw new IOException("Could not read " + path, e);
            }
        }

        @Override
        public long skip(final long n) throws IOException {
            try {
                return super.skip(n);
            } catch (final SecurityException e) {
                throw new IOException("Could not read " + path, e);
            }
        }
    }

    /**
     * Read the named resource from a {@link ModuleReader} as a {@link ByteBuffer}. The caller must release the
     * buffer with {@link ModuleReader#release(ByteBuffer)} when finished.
     *
     * @param moduleReader
     *            the module reader.
     * @param path
     *            the resource path to read.
     * @return a {@link ByteBuffer} containing the resource.
     * @throws IOException
     *             if the resource could not be read.
     * @throws OutOfMemoryError
     *             if the resource is larger than 2GB, the maximum capacity of a {@link ByteBuffer}.
     */
    public static ByteBuffer read(final ModuleReader moduleReader, final String path)
            throws IOException, OutOfMemoryError {
        final Optional<ByteBuffer> optionalByteBuffer;
        try {
            optionalByteBuffer = moduleReader.read(path);
        } catch (final IOException | SecurityException e) {
            throw new IOException("Could not call ModuleReader#read(String) for path " + path, e);
        }
        return getValue(optionalByteBuffer, moduleReader, "read", path);
    }

    /**
     * Check whether a module contains a non-directory resource at the given path without listing the module's
     * contents.
     *
     * @param moduleReader
     *            the module reader.
     * @param path
     *            the resource path to look for.
     * @return true if the module contains a non-directory resource at {@code path}.
     * @throws IOException
     *             if the module could not be searched.
     */
    public static boolean contains(final ModuleReader moduleReader, final String path) throws IOException {
        final Optional<URI> optionalURI;
        try {
            optionalURI = moduleReader.find(path);
        } catch (final IOException | SecurityException e) {
            throw new IOException("Could not call ModuleReader#find(String) for path " + path, e);
        }
        return optionalURI != null && optionalURI.isPresent() && !optionalURI.get().toString().endsWith("/");
    }

    /**
     * Locate the named resource in a {@link ModuleReader} as a {@link URI}.
     *
     * @param moduleReader
     *            the module reader.
     * @param path
     *            the resource path to locate.
     * @return a {@link URI} for the resource.
     * @throws IOException
     *             if the resource could not be located.
     */
    public static URI find(final ModuleReader moduleReader, final String path) throws IOException {
        final Optional<URI> optionalURI;
        try {
            optionalURI = moduleReader.find(path);
        } catch (final IOException | SecurityException e) {
            throw new IOException("Could not call ModuleReader#find(String) for path " + path, e);
        }
        return getValue(optionalURI, moduleReader, "find", path);
    }
}
