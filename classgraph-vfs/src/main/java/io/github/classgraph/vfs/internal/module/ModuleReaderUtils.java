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

import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.github.classgraph.base.LogNode;
import org.jspecify.annotations.Nullable;

/**
 * Helper methods for opening a {@link ModuleReference} and for calling {@link ModuleReader}, which convert its
 * {@link Optional} and {@link Stream} return values into plain values.
 *
 * <p>
 * Every way in which a module can fail to be read is reported as an {@link IOException}: the {@link IOException}
 * thrown by the {@link ModuleReader} method itself, a resource that the module does not contain, and a
 * {@code ModuleReader} implementation that returns null where its contract does not permit it. The one exception is
 * a {@link SecurityException}, which only {@link #openModule(ModuleReference)} wraps: the rest declare it, so that
 * a caller can tell a module it is not allowed to read from one that cannot be read.
 */
public final class ModuleReaderUtils {
    /** Class can not be constructed. */
    private ModuleReaderUtils() {
        // Empty
    }

    /**
     * Open a module, returning a {@link ModuleReader} for reading its contents.
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
            // null return means the ModuleReference implementation does not honour its contract
            throw new IOException("ModuleReference#open() returned null for module "
                    + moduleReference.descriptor().name() + ", which its contract does not permit -- this is a bug "
                    + "in the ModuleReference implementation " + moduleReference.getClass().getName());
        }
        return moduleReader;
    }

    /**
     * Get the list of resources accessible to a {@link ModuleReader}, logging to the given {@link LogNode} if the
     * {@code ModuleReader} does not honour its contract.
     *
     * From the documentation for ModuleReader#list(): "Whether the stream of elements includes names corresponding
     * to directories in the module is module reader specific. In lazy implementations then an IOException may be
     * thrown when using the stream to list the module contents. If this occurs then the IOException will be wrapped
     * in an java.io.UncheckedIOException and thrown from the method that caused the access to be attempted.
     * SecurityException may also be thrown when using the stream to list the module contents and access is denied
     * by the security manager."
     *
     * @param moduleReader
     *            the module reader.
     * @param moduleName
     *            the name of the module being read, for error messages.
     * @param log
     *            the log, or null for no logging.
     * @return A list of the paths of resources in the module. The list is mutable, since callers sort it in place,
     *         and it is empty rather than null if the module reader listed its contents as null.
     * @throws IOException
     *             If the contents of the module could not be listed.
     * @throws SecurityException
     *             If the module cannot be accessed.
     */
    public static List<String> list(final ModuleReader moduleReader, final String moduleName,
            final @Nullable LogNode log) throws IOException, SecurityException {
        final Stream<String> resourcesStream;
        try {
            resourcesStream = moduleReader.list();
        } catch (final IOException e) {
            throw new IOException("Could not call ModuleReader#list() for module " + moduleName, e);
        }
        if (resourcesStream == null) {
            // ModuleReader#list() is specified to return a Stream<String>, and is not allowed to return null, so a
            // null return means the ModuleReader implementation does not honour its contract. Some do anyway --
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
            // N.B. the returned list must be mutable, since ClasspathElementModule sorts it in place (so
            // Stream#toList() cannot be used here)
            return resourcesStream.collect(Collectors.toCollection(ArrayList::new));
        }
    }

    /**
     * Use a {@link ModuleReader} to open the named resource as an {@link InputStream}.
     *
     * @param moduleReader
     *            the module reader.
     * @param path
     *            The path to the resource to open.
     * @return An {@link InputStream} for the content of the resource.
     * @throws IOException
     *             If the resource could not be opened.
     * @throws SecurityException
     *             If the module cannot be accessed.
     */
    public static InputStream open(final ModuleReader moduleReader, final String path)
            throws IOException, SecurityException {
        final Optional<InputStream> optionalInputStream;
        try {
            optionalInputStream = moduleReader.open(path);
        } catch (final IOException e) {
            throw new IOException("Could not call ModuleReader#open(String) for path " + path, e);
        }
        if (optionalInputStream == null) {
            throw new IOException("Got null result from ModuleReader#open(String) for path " + path);
        }
        final var inputStream = optionalInputStream.orElse(null);
        if (inputStream == null) {
            throw new IOException("Got null result from ModuleReader#open(String)#get()");
        }
        return inputStream;
    }

    /**
     * Use a {@link ModuleReader} to open the named resource as a {@link ByteBuffer}. Call
     * {@link ModuleReader#release(ByteBuffer)} when you have finished with the {@link ByteBuffer}.
     *
     * @param moduleReader
     *            the module reader.
     * @param path
     *            The path to the resource to open.
     * @return A {@link ByteBuffer} for the content of the resource.
     * @throws IOException
     *             If the resource could not be read.
     * @throws SecurityException
     *             If the module cannot be accessed.
     * @throws OutOfMemoryError
     *             if the resource is larger than 2GB, the maximum capacity of a byte buffer.
     */
    public static ByteBuffer read(final ModuleReader moduleReader, final String path)
            throws IOException, SecurityException, OutOfMemoryError {
        final Optional<ByteBuffer> optionalByteBuffer;
        try {
            optionalByteBuffer = moduleReader.read(path);
        } catch (final IOException e) {
            throw new IOException("Could not call ModuleReader#read(String) for path " + path, e);
        }
        if (optionalByteBuffer == null) {
            throw new IOException("Got null result from ModuleReader#read(String)");
        }
        final var byteBuffer = optionalByteBuffer.orElse(null);
        if (byteBuffer == null) {
            throw new IOException("Got null result from ModuleReader#read(String).get()");
        }
        return byteBuffer;
    }

    /**
     * Use a {@link ModuleReader} to test whether the module contains the named resource, without listing the
     * contents of the module.
     *
     * @param moduleReader
     *            the module reader.
     * @param path
     *            The path to the resource to look for.
     * @return true if the module contains the named resource.
     * @throws IOException
     *             If the module could not be searched for the resource.
     * @throws SecurityException
     *             If the module cannot be accessed.
     */
    public static boolean contains(final ModuleReader moduleReader, final String path)
            throws IOException, SecurityException {
        final Optional<URI> optionalURI;
        try {
            optionalURI = moduleReader.find(path);
        } catch (final IOException e) {
            throw new IOException("Could not call ModuleReader#find(String) for path " + path, e);
        }
        return optionalURI != null && optionalURI.isPresent();
    }

    /**
     * Use a {@link ModuleReader} to find the named resource as a {@link URI}.
     *
     * @param moduleReader
     *            the module reader.
     * @param path
     *            The path to the resource to open.
     * @return A {@link URI} for the resource.
     * @throws IOException
     *             If the resource could not be located.
     * @throws SecurityException
     *             If the module cannot be accessed.
     */
    public static URI find(final ModuleReader moduleReader, final String path)
            throws IOException, SecurityException {
        final Optional<URI> optionalURI;
        try {
            optionalURI = moduleReader.find(path);
        } catch (final IOException e) {
            throw new IOException("Could not call ModuleReader#find(String) for path " + path, e);
        }
        if (optionalURI == null) {
            throw new IOException("Got null result from ModuleReader#find(String)");
        }
        final var uri = optionalURI.orElse(null);
        if (uri == null) {
            throw new IOException("Got null result from ModuleReader#find(String).get()");
        }
        return uri;
    }
}
