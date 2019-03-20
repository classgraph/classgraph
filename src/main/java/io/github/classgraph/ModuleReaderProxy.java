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
 * Copyright (c) 2019 Luke Hutchison
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
package io.github.classgraph;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.List;

import nonapi.io.github.classgraph.utils.ReflectionUtils;

/** A ModuleReader proxy, written using reflection to preserve backwards compatibility with JDK 7 and 8. */
public class ModuleReaderProxy implements Closeable {
    /** The module reader. */
    private final AutoCloseable moduleReader;

    /** Class<Collector> collectorClass = Class.forName("java.util.stream.Collector"); */
    private static Class<?> collectorClass;

    /** Collector<Object, ?, List<Object>> collectorsToList = Collectors.toList(); */
    private static Object collectorsToList;

    static {
        collectorClass = ReflectionUtils.classForNameOrNull("java.util.stream.Collector");
        final Class<?> collectorsClass = ReflectionUtils.classForNameOrNull("java.util.stream.Collectors");
        if (collectorsClass != null) {
            collectorsToList = ReflectionUtils.invokeStaticMethod(collectorsClass, "toList",
                    /* throwException = */ true);
        }
    }

    /**
     * Constructor.
     *
     * @param moduleRef
     *            the module ref
     * @throws IOException
     *             If an I/O exception occurs.
     */
    ModuleReaderProxy(final ModuleRef moduleRef) throws IOException {
        try {
            moduleReader = (AutoCloseable) ReflectionUtils.invokeMethod(moduleRef.getReference(), "open",
                    /* throwException = */ true);
            if (moduleReader == null) {
                throw new IllegalArgumentException("moduleReference.open() should not return null");
            }
        } catch (final SecurityException e) {
            throw new IOException("Could not open module " + moduleRef.getName(), e);
        }
    }

    /** Calls ModuleReader#close(). */
    @Override
    public void close() {
        try {
            moduleReader.close();
        } catch (final Exception e) {
            // Ignore
        }
    }

    /**
     * Get the list of resources accessible to a ModuleReader.
     * 
     * From the documentation for ModuleReader#list(): "Whether the stream of elements includes names corresponding
     * to directories in the module is module reader specific. In lazy implementations then an IOException may be
     * thrown when using the stream to list the module contents. If this occurs then the IOException will be wrapped
     * in an java.io.UncheckedIOException and thrown from the method that caused the access to be attempted.
     * SecurityException may also be thrown when using the stream to list the module contents and access is denied
     * by the security manager."
     * 
     * @return A list of the paths of resources in the module.
     * @throws SecurityException
     *             If the module cannot be accessed.
     */
    public List<String> list() throws SecurityException {
        if (collectorsToList == null) {
            throw new IllegalArgumentException("Could not call Collectors.toList()");
        }
        final Object /* Stream<String> */ resourcesStream = ReflectionUtils.invokeMethod(moduleReader, "list",
                /* throwException = */ true);
        if (resourcesStream == null) {
            throw new IllegalArgumentException("Could not call moduleReader.list()");
        }
        final Object resourcesList = ReflectionUtils.invokeMethod(resourcesStream, "collect", collectorClass,
                collectorsToList, /* throwException = */ true);
        if (resourcesList == null) {
            throw new IllegalArgumentException("Could not call moduleReader.list().collect(Collectors.toList())");
        }
        @SuppressWarnings("unchecked")
        final List<String> resourcesListTyped = (List<String>) resourcesList;
        return resourcesListTyped;
    }

    /**
     * Use the proxied ModuleReader to open the named resource as an InputStream.
     * 
     * @param path
     *            The path to the resource to open.
     * @param open
     *            if true, call moduleReader.open(name).get() (returning an InputStream), otherwise call
     *            moduleReader.read(name).get() (returning a ByteBuffer).
     * @return An {@link InputStream} for the content of the resource.
     * @throws SecurityException
     *             If the module cannot be accessed.
     */
    private Object openOrRead(final String path, final boolean open) throws SecurityException {
        final String methodName = open ? "open" : "read";
        final Object /* Optional<InputStream> */ optionalInputStream = ReflectionUtils.invokeMethod(moduleReader,
                methodName, String.class, path, /* throwException = */ true);
        if (optionalInputStream == null) {
            throw new IllegalArgumentException("Got null result from moduleReader." + methodName + "(name)");
        }
        final Object /* InputStream */ inputStream = ReflectionUtils.invokeMethod(optionalInputStream, "get",
                /* throwException = */ true);
        if (inputStream == null) {
            throw new IllegalArgumentException("Got null result from moduleReader." + methodName + "(name).get()");
        }
        return inputStream;
    }

    /**
     * Use the proxied ModuleReader to open the named resource as an InputStream.
     * 
     * @param path
     *            The path to the resource to open.
     * @return An {@link InputStream} for the content of the resource.
     * @throws SecurityException
     *             If the module cannot be accessed.
     */
    public InputStream open(final String path) throws SecurityException {
        return (InputStream) openOrRead(path, /* open = */ true);
    }

    /**
     * Use the proxied ModuleReader to open the named resource as a ByteBuffer. Call release(byteBuffer) when you
     * have finished with the ByteBuffer.
     * 
     * @param path
     *            The path to the resource to open.
     * @return A {@link ByteBuffer} for the content of the resource.
     * @throws SecurityException
     *             If the module cannot be accessed.
     * @throws OutOfMemoryError
     *             if the resource is larger than 2GB, the maximum capacity of a byte buffer.
     */
    public ByteBuffer read(final String path) throws SecurityException, OutOfMemoryError {
        return (ByteBuffer) openOrRead(path, /* open = */ false);
    }

    /**
     * Release a {@link ByteBuffer} allocated by calling {@link #read(String)}.
     * 
     * @param byteBuffer
     *            The {@link ByteBuffer} to release.
     */
    public void release(final ByteBuffer byteBuffer) {
        ReflectionUtils.invokeMethod(moduleReader, "release", ByteBuffer.class, byteBuffer,
                /* throwException = */ true);
    }
}