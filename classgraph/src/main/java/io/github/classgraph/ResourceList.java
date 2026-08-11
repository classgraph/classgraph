/*
 * This file is part of ClassGraph.
 *
 * Author: Luke Hutchison (luke.hutch@gmail.com)
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

import static io.github.classgraph.PotentiallyUnmodifiableList.unmodifiable;

import java.io.IOException;
import java.io.Serial;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.function.Predicate;

import io.github.classgraph.base.internal.utils.Assert;
import io.github.classgraph.base.internal.utils.CollectionUtils;

/**
 * An {@link AutoCloseable} list of {@link AutoCloseable} {@link Resource} objects. Closing the list closes every
 * {@link Resource} in the list, releasing any open file handles or memory mappings.
 *
 * <p>
 * Lists returned by the ClassGraph API are unmodifiable: any attempt to add, remove, replace or sort their elements
 * throws {@link UnsupportedOperationException}. Copy the list if you need a modifiable version of it, e.g.
 * {@code new ArrayList<>(list)}.
 */
public class ResourceList extends PotentiallyUnmodifiableList<Resource> implements AutoCloseable {
    /** serialVersionUID. */
    @Serial
    private static final long serialVersionUID = 1L;

    /** An unmodifiable empty {@link ResourceList}. */
    static final ResourceList EMPTY_LIST = new ResourceList();
    static {
        EMPTY_LIST.makeUnmodifiable();
    }

    /**
     * Return an unmodifiable empty {@link ResourceList}.
     *
     * @return the unmodifiable empty {@link ResourceList}.
     */
    public static ResourceList emptyList() {
        return EMPTY_LIST;
    }

    /**
     * Create a new modifiable empty list of {@link Resource} objects.
     */
    public ResourceList() {
        super();
    }

    /**
     * Create a new modifiable empty list of {@link Resource} objects, given a size hint.
     *
     * @param sizeHint
     *            the expected number of elements
     */
    public ResourceList(final int sizeHint) {
        super(sizeHint);
    }

    /**
     * Create a new modifiable empty {@link ResourceList}, given an initial collection of {@link Resource} objects.
     *
     * @param resourceCollection
     *            the collection of {@link Resource} objects.
     */
    public ResourceList(final Collection<Resource> resourceCollection) {
        // Objects.requireNonNull rather than Assert.notNull, since Assert.notNull returns void, and so cannot be
        // called before the super() call
        super(Objects.requireNonNull(resourceCollection, "resourceCollection must not be null"));
    }

    /**
     * Returns a list of all resources with the requested path. (There may be more than one resource with a given
     * path, from different classpath elements or modules, so this returns a {@link ResourceList} rather than a
     * single {@link Resource}.)
     *
     * @param resourcePath
     *            The path of a resource
     * @return A {@link ResourceList} of {@link Resource} objects in this list that have the given path (there may
     *         be more than one resource with a given path, from different classpath elements or modules, so this
     *         returns a {@link ResourceList} rather than a single {@link Resource}.) Returns the empty list if no
     *         resource with is found with a matching path.
     */
    public ResourceList get(final String resourcePath) {
        Assert.notNull(resourcePath, "resourcePath");
        var hasResourceWithPath = false;
        for (final Resource res : this) {
            if (res.getPath().equals(resourcePath)) {
                hasResourceWithPath = true;
                break;
            }
        }
        if (!hasResourceWithPath) {
            return EMPTY_LIST;
        } else {
            final var matchingResources = new ResourceList(2);
            for (final Resource res : this) {
                if (res.getPath().equals(resourcePath)) {
                    matchingResources.add(res);
                }
            }
            return unmodifiable(matchingResources);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the paths of all resources in this list relative to the package root.
     *
     * @return The paths of all resources in this list relative to the package root, by calling
     *         {@link Resource#getPath()} for each item in the list.
     */
    public List<String> getPaths() {
        final List<String> resourcePaths = new ArrayList<>(this.size());
        for (final Resource resource : this) {
            resourcePaths.add(resource.getPath());
        }
        return Collections.unmodifiableList(resourcePaths);
    }

    /**
     * Get the paths of all resources in this list relative to the root of the classpath element.
     *
     * @return The paths of all resources in this list relative to the root of the classpath element, by calling
     *         {@link Resource#getPathRelativeToClasspathElement()} for each item in the list.
     */
    public List<String> getPathsRelativeToClasspathElement() {
        final List<String> resourcePaths = new ArrayList<>(this.size());
        for (final Resource resource : this) {
            resourcePaths.add(resource.getPathRelativeToClasspathElement());
        }
        return Collections.unmodifiableList(resourcePaths);
    }

    /**
     * Get the URLs of all resources in this list, by calling {@link Resource#getURL()} for each item in the list.
     *
     * @return The URLs of all resources in this list.
     * @throws IllegalStateException
     *             if any resource's URI could not be converted to a {@link URL}.
     */
    public List<URL> getURLs() {
        final List<URL> resourceURLs = new ArrayList<>(this.size());
        for (final Resource resource : this) {
            resourceURLs.add(resource.getURL());
        }
        return Collections.unmodifiableList(resourceURLs);
    }

    /**
     * Get the URIs of all resources in this list, by calling {@link Resource#getURI()} for each item in the list.
     *
     * @return The URIs of all resources in this list.
     */
    public List<URI> getURIs() {
        final List<URI> resourceURLs = new ArrayList<>(this.size());
        for (final Resource resource : this) {
            resourceURLs.add(resource.getURI());
        }
        return Collections.unmodifiableList(resourceURLs);
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Returns true if a Resource has a path ending in ".class". */
    private static final Predicate<Resource> CLASSFILE_FILTER = resource -> {
        final var path = resource.getPath();
        if (!path.endsWith(".class") || path.length() < 7) {
            return false;
        }
        // Check filename is not simply ".class"
        final var c = path.charAt(path.length() - 7);
        return c != '/' && c != '.';
    };

    /**
     * Return a new {@link ResourceList} consisting of only the resources with the filename extension ".class".
     *
     * @return A new {@link ResourceList} consisting of only the resources with the filename extension ".class".
     */
    public ResourceList classFilesOnly() {
        return filter(CLASSFILE_FILTER);
    }

    /**
     * Return a new {@link ResourceList} consisting of non-classfile resources only.
     *
     * @return A new {@link ResourceList} consisting of only the resources that do not have the filename extension
     *         ".class".
     */
    public ResourceList nonClassFilesOnly() {
        return filter(CLASSFILE_FILTER.negate());
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Return this {@link ResourceList} as a map from resource path (obtained from {@link Resource#getPath()}) to a
     * {@link ResourceList} of {@link Resource} objects that have that path.
     *
     * @return This {@link ResourceList} as a map from resource path (obtained from {@link Resource#getPath()}) to a
     *         {@link ResourceList} of {@link Resource} objects that have that path.
     */
    public Map<String, ResourceList> asMap() {
        final Map<String, ResourceList> pathToResourceList = new HashMap<>();
        for (final Resource resource : this) {
            pathToResourceList.computeIfAbsent(resource.getPath(), path -> new ResourceList(1)).add(resource);
        }
        for (final ResourceList resourceList : pathToResourceList.values()) {
            resourceList.makeUnmodifiable();
        }
        return Collections.unmodifiableMap(pathToResourceList);
    }

    /**
     * Find duplicate resource paths within this {@link ResourceList}.
     *
     * @return A {@link List} of {@link Entry} objects for all resources in the classpath and/or module path that
     *         have a non-unique path (i.e. where there are at least two resources with the same path). The key of
     *         each returned {@link Entry} is the path (obtained from {@link Resource#getPath()}), and the value is
     *         a {@link ResourceList} of at least two unique {@link Resource} objects that have that path.
     */
    public List<Entry<String, ResourceList>> findDuplicatePaths() {
        final List<Entry<String, ResourceList>> duplicatePaths = new ArrayList<>();
        for (final Entry<String, ResourceList> pathAndResourceList : asMap().entrySet()) {
            // Find ResourceLists with two or more entries
            if (pathAndResourceList.getValue().size() > 1) {
                duplicatePaths.add(new SimpleEntry<>(pathAndResourceList.getKey(), pathAndResourceList.getValue()));
            }
        }
        // Sort in lexicographic order of path
        CollectionUtils.sortIfNotEmpty(duplicatePaths, Comparator.comparing(Entry<String, ResourceList>::getKey));
        return Collections.unmodifiableList(duplicatePaths);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Find the subset of the {@link Resource} objects in this list for which the given filter predicate is true.
     *
     * @param filter
     *            The filter to apply. Only the {@link Resource} objects for which the filter returns true are
     *            copied to the returned list.
     * @return The subset of the {@link Resource} objects in this list for which the given filter predicate is true.
     */
    public ResourceList filter(final Predicate<Resource> filter) {
        Assert.notNull(filter, "filter");
        final var resourcesFiltered = new ResourceList();
        for (final Resource resource : this) {
            if (filter.test(resource)) {
                resourcesFiltered.add(resource);
            }
        }
        return unmodifiable(resourcesFiltered);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * A {@link FunctionalInterface} for consuming the contents of a {@link Resource} as a byte array.
     */
    @FunctionalInterface
    public interface ByteArrayConsumer {
        /**
         * Consume the complete content of a {@link Resource} as a byte array.
         *
         * @param resource
         *            The {@link Resource} used to load the byte array.
         * @param byteArray
         *            The complete content of the resource.
         * @throws IOException
         *             if an IO exception occurs.
         */
        void accept(final Resource resource, final byte[] byteArray) throws IOException;
    }

    /**
     * Fetch the content of each {@link Resource} in this {@link ResourceList} as a byte array, pass the byte array
     * to the given {@link ByteArrayConsumer}, then close the underlying InputStream or release the underlying
     * ByteBuffer by calling {@link Resource#close()}.
     *
     * @param byteArrayConsumer
     *            The {@link ByteArrayConsumer}.
     * @return this (for method chaining).
     * @throws IOException
     *             if loading any of the resources, or the consumer itself, throws {@link IOException}.
     */
    public ResourceList forEachByteArray(final ByteArrayConsumer byteArrayConsumer) throws IOException {
        Assert.notNull(byteArrayConsumer, "byteArrayConsumer");
        for (final Resource resource : this) {
            try (resource) {
                byteArrayConsumer.accept(resource, resource.load());
            }
        }
        return this;
    }

    /**
     * The same as {@link #forEachByteArray(ByteArrayConsumer)}, but if loading a resource, or the consumer itself,
     * throws {@link IOException}, that resource is silently skipped and the iteration continues.
     *
     * @param byteArrayConsumer
     *            The {@link ByteArrayConsumer}.
     * @return this (for method chaining).
     */
    public ResourceList forEachByteArrayIgnoringIOException(final ByteArrayConsumer byteArrayConsumer) {
        Assert.notNull(byteArrayConsumer, "byteArrayConsumer");
        for (final Resource resource : this) {
            try (resource) {
                byteArrayConsumer.accept(resource, resource.load());
            } catch (final IOException e) {
                // Ignore
            }
        }
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * A {@link FunctionalInterface} for consuming the contents of a {@link Resource} as an {@link InputStream}.
     */
    @FunctionalInterface
    public interface InputStreamConsumer {
        /**
         * Consume a {@link Resource} as an {@link InputStream}.
         *
         * @param resource
         *            The {@link Resource} used to open the {@link InputStream}.
         * @param inputStream
         *            The {@link InputStream} opened on the resource.
         * @throws IOException
         *             if an IO exception occurs.
         */
        void accept(final Resource resource, final InputStream inputStream) throws IOException;
    }

    /**
     * Fetch an {@link InputStream} for each {@link Resource} in this {@link ResourceList}, pass the
     * {@link InputStream} to the given {@link InputStreamConsumer}, then close the {@link InputStream} after the
     * {@link InputStreamConsumer} returns, by calling {@link Resource#close()}.
     *
     * @param inputStreamConsumer
     *            The {@link InputStreamConsumer}.
     * @return this (for method chaining).
     * @throws IOException
     *             if opening any of the resources, or the consumer itself, throws {@link IOException}.
     */
    public ResourceList forEachInputStream(final InputStreamConsumer inputStreamConsumer) throws IOException {
        Assert.notNull(inputStreamConsumer, "inputStreamConsumer");
        for (final Resource resource : this) {
            try (resource) {
                inputStreamConsumer.accept(resource, resource.open());
            }
        }
        return this;
    }

    /**
     * The same as {@link #forEachInputStream(InputStreamConsumer)}, but if opening a resource, or the consumer
     * itself, throws {@link IOException}, that resource is silently skipped and the iteration continues.
     *
     * @param inputStreamConsumer
     *            The {@link InputStreamConsumer}.
     * @return this (for method chaining).
     */
    public ResourceList forEachInputStreamIgnoringIOException(final InputStreamConsumer inputStreamConsumer) {
        Assert.notNull(inputStreamConsumer, "inputStreamConsumer");
        for (final Resource resource : this) {
            try (resource) {
                inputStreamConsumer.accept(resource, resource.open());
            } catch (final IOException e) {
                // Ignore
            }
        }
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * A {@link FunctionalInterface} for consuming the contents of a {@link Resource} as a {@link ByteBuffer}.
     */
    @FunctionalInterface
    public interface ByteBufferConsumer {
        /**
         * Consume a {@link Resource} as a {@link ByteBuffer}.
         *
         * @param resource
         *            The {@link Resource} whose content is reflected in the {@link ByteBuffer}.
         * @param byteBuffer
         *            The {@link ByteBuffer} mapped to the resource.
         * @throws IOException
         *             if an IO exception occurs.
         */
        void accept(final Resource resource, final ByteBuffer byteBuffer) throws IOException;
    }

    /**
     * Read each {@link Resource} in this {@link ResourceList} as a {@link ByteBuffer}, pass the {@link ByteBuffer}
     * to the given {@link ByteBufferConsumer}, then release the {@link ByteBuffer} after the
     * {@link ByteBufferConsumer} returns, by calling {@link Resource#close()}.
     *
     * @param byteBufferConsumer
     *            The {@link ByteBufferConsumer}.
     * @return this (for method chaining).
     * @throws IOException
     *             if reading any of the resources, or the consumer itself, throws {@link IOException}.
     */
    public ResourceList forEachByteBuffer(final ByteBufferConsumer byteBufferConsumer) throws IOException {
        Assert.notNull(byteBufferConsumer, "byteBufferConsumer");
        for (final Resource resource : this) {
            try (resource) {
                byteBufferConsumer.accept(resource, resource.read());
            }
        }
        return this;
    }

    /**
     * The same as {@link #forEachByteBuffer(ByteBufferConsumer)}, but if reading a resource, or the consumer
     * itself, throws {@link IOException}, that resource is silently skipped and the iteration continues.
     *
     * @param byteBufferConsumer
     *            The {@link ByteBufferConsumer}.
     * @return this (for method chaining).
     */
    public ResourceList forEachByteBufferIgnoringIOException(final ByteBufferConsumer byteBufferConsumer) {
        Assert.notNull(byteBufferConsumer, "byteBufferConsumer");
        for (final Resource resource : this) {
            try (resource) {
                byteBufferConsumer.accept(resource, resource.read());
            } catch (final IOException e) {
                // Ignore
            }
        }
        return this;
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Close all the {@link Resource} objects in this {@link ResourceList}. */
    @Override
    public void close() {
        for (final Resource resource : this) {
            resource.close();
        }
    }
}
