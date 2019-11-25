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

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import nonapi.io.github.classgraph.utils.CollectionUtils;

/** An AutoCloseable list of AutoCloseable {@link Resource} objects. */
public class ResourceList extends PotentiallyUnmodifiableList<Resource> implements AutoCloseable {
    /** serialVersionUID. */
    static final long serialVersionUID = 1L;

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
     *            the size hint
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
        super(resourceCollection);
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
        boolean hasResourceWithPath = false;
        for (final Resource res : this) {
            if (res.getPath().equals(resourcePath)) {
                hasResourceWithPath = true;
                break;
            }
        }
        if (!hasResourceWithPath) {
            return EMPTY_LIST;
        } else {
            final ResourceList matchingResources = new ResourceList(2);
            for (final Resource res : this) {
                if (res.getPath().equals(resourcePath)) {
                    matchingResources.add(res);
                }
            }
            return matchingResources;
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
        return resourcePaths;
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
            resourcePaths.add(resource.getPath());
        }
        return resourcePaths;
    }

    /**
     * Get the URLs of all resources in this list, by calling {@link Resource#getURL()} for each item in the list.
     * Note that any resource with a {@code jrt:} URI (e.g. a system resource, or a resource from a jlink'd image)
     * will cause {@link IllegalArgumentException} to be thrown, since {@link URL} does not support this scheme, so
     * {@link #getURIs()} is strongly preferred over {@link #getURLs()}.
     *
     * @return The URLs of all resources in this list.
     */
    public List<URL> getURLs() {
        final List<URL> resourceURLs = new ArrayList<>(this.size());
        for (final Resource resource : this) {
            resourceURLs.add(resource.getURL());
        }
        return resourceURLs;
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
        return resourceURLs;
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Returns true if a Resource has a path ending in ".class". */
    private static final ResourceFilter CLASSFILE_FILTER = new ResourceFilter() {
        @Override
        public boolean accept(final Resource resource) {
            final String path = resource.getPath();
            if (!path.endsWith(".class") || path.length() < 7) {
                return false;
            }
            // Check filename is not simply ".class"
            final char c = path.charAt(path.length() - 7);
            return c != '/' && c != '.';
        }
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
        return filter(new ResourceFilter() {
            @Override
            public boolean accept(final Resource resource) {
                return !CLASSFILE_FILTER.accept(resource);
            }
        });
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
            final String path = resource.getPath();
            ResourceList resourceList = pathToResourceList.get(path);
            if (resourceList == null) {
                resourceList = new ResourceList(1);
                pathToResourceList.put(path, resourceList);
            }
            resourceList.add(resource);
        }
        return pathToResourceList;
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
        CollectionUtils.sortIfNotEmpty(duplicatePaths, new Comparator<Entry<String, ResourceList>>() {
            @Override
            public int compare(final Entry<String, ResourceList> o1, final Entry<String, ResourceList> o2) {
                // Sort in lexicographic order of path
                return o1.getKey().compareTo(o2.getKey());
            }
        });
        return duplicatePaths;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Filter a {@link ResourceList} using a predicate mapping a {@link Resource} object to a boolean, producing
     * another {@link ResourceList} for all items in the list for which the predicate is true.
     */
    @FunctionalInterface
    public interface ResourceFilter {
        /**
         * Whether or not to allow a {@link Resource} list item through the filter.
         *
         * @param resource
         *            The {@link Resource} item to filter.
         * @return Whether or not to allow the item through the filter. If true, the item is copied to the output
         *         list; if false, it is excluded.
         */
        boolean accept(Resource resource);
    }

    /**
     * Find the subset of the {@link Resource} objects in this list for which the given filter predicate is true.
     *
     * @param filter
     *            The {@link ResourceFilter} to apply.
     * @return The subset of the {@link Resource} objects in this list for which the given filter predicate is true.
     */
    public ResourceList filter(final ResourceFilter filter) {
        final ResourceList resourcesFiltered = new ResourceList();
        for (final Resource resource : this) {
            if (filter.accept(resource)) {
                resourcesFiltered.add(resource);
            }
        }
        return resourcesFiltered;
    }

    // -------------------------------------------------------------------------------------------------------------

    /** A {@link FunctionalInterface} for consuming the contents of a {@link Resource} as a byte array. */
    @FunctionalInterface
    public interface ByteArrayConsumer {
        /**
         * Consume the complete content of a {@link Resource} as a byte array.
         * 
         * @param resource
         *            The {@link Resource} used to load the byte array.
         * @param byteArray
         *            The complete content of the resource.
         */
        void accept(final Resource resource, final byte[] byteArray);
    }

    /**
     * Fetch the content of each {@link Resource} in this {@link ResourceList} as a byte array, pass the byte array
     * to the given {@link ByteArrayConsumer}, then close the underlying InputStream or release the underlying
     * ByteBuffer by calling {@link Resource#close()}.
     * 
     * @param byteArrayConsumer
     *            The {@link ByteArrayConsumer}.
     * @param ignoreIOExceptions
     *            if true, any {@link IOException} thrown while trying to load any of the resources will be silently
     *            ignored.
     * @throws IllegalArgumentException
     *             if ignoreExceptions is false, and an {@link IOException} is thrown while trying to load any of
     *             the resources.
     */
    public void forEachByteArray(final ByteArrayConsumer byteArrayConsumer, final boolean ignoreIOExceptions) {
        for (final Resource resource : this) {
            try {
                final byte[] resourceContent = resource.load();
                byteArrayConsumer.accept(resource, resourceContent);
            } catch (final IOException e) {
                if (!ignoreIOExceptions) {
                    throw new IllegalArgumentException("Could not load resource " + resource, e);
                }
            } finally {
                resource.close();
            }
        }
    }

    /**
     * Fetch the content of each {@link Resource} in this {@link ResourceList} as a byte array, pass the byte array
     * to the given {@link ByteArrayConsumer}, then close the underlying InputStream or release the underlying
     * ByteBuffer by calling {@link Resource#close()}.
     * 
     * @param byteArrayConsumer
     *            The {@link ByteArrayConsumer}.
     * @throws IllegalArgumentException
     *             if trying to load any of the resources results in an {@link IOException} being thrown.
     */
    public void forEachByteArray(final ByteArrayConsumer byteArrayConsumer) {
        forEachByteArray(byteArrayConsumer, /* ignoreIOExceptions = */ false);
    }

    // -------------------------------------------------------------------------------------------------------------

    /** A {@link FunctionalInterface} for consuming the contents of a {@link Resource} as an {@link InputStream}. */
    @FunctionalInterface
    public interface InputStreamConsumer {
        /**
         * Consume a {@link Resource} as an {@link InputStream}.
         * 
         * @param resource
         *            The {@link Resource} used to open the {@link InputStream}.
         * @param inputStream
         *            The {@link InputStream} opened on the resource.
         */
        void accept(final Resource resource, final InputStream inputStream);
    }

    /**
     * Fetch an {@link InputStream} for each {@link Resource} in this {@link ResourceList}, pass the
     * {@link InputStream} to the given {@link InputStreamConsumer}, then close the {@link InputStream} after the
     * {@link InputStreamConsumer} returns, by calling {@link Resource#close()}.
     * 
     * @param inputStreamConsumer
     *            The {@link InputStreamConsumer}.
     * @param ignoreIOExceptions
     *            if true, any {@link IOException} thrown while trying to load any of the resources will be silently
     *            ignored.
     * @throws IllegalArgumentException
     *             if ignoreExceptions is false, and an {@link IOException} is thrown while trying to open any of
     *             the resources.
     */
    public void forEachInputStream(final InputStreamConsumer inputStreamConsumer,
            final boolean ignoreIOExceptions) {
        for (final Resource resource : this) {
            try {
                inputStreamConsumer.accept(resource, resource.open());
            } catch (final IOException e) {
                if (!ignoreIOExceptions) {
                    throw new IllegalArgumentException("Could not load resource " + resource, e);
                }
            } finally {
                resource.close();
            }
        }
    }

    /**
     * Fetch an {@link InputStream} for each {@link Resource} in this {@link ResourceList}, pass the
     * {@link InputStream} to the given {@link InputStreamConsumer}, then close the {@link InputStream} after the
     * {@link InputStreamConsumer} returns, by calling {@link Resource#close()}.
     * 
     * @param inputStreamConsumer
     *            The {@link InputStreamConsumer}.
     * @throws IllegalArgumentException
     *             if trying to open any of the resources results in an {@link IOException} being thrown.
     */
    public void forEachInputStream(final InputStreamConsumer inputStreamConsumer) {
        forEachInputStream(inputStreamConsumer, /* ignoreIOExceptions = */ false);
    }

    // -------------------------------------------------------------------------------------------------------------

    /** A {@link FunctionalInterface} for consuming the contents of a {@link Resource} as a {@link ByteBuffer}. */
    @FunctionalInterface
    public interface ByteBufferConsumer {
        /**
         * Consume a {@link Resource} as a {@link ByteBuffer}.
         * 
         * @param resource
         *            The {@link Resource} whose content is reflected in the {@link ByteBuffer}.
         * @param byteBuffer
         *            The {@link ByteBuffer} mapped to the resource.
         */
        void accept(final Resource resource, final ByteBuffer byteBuffer);
    }

    /**
     * Read each {@link Resource} in this {@link ResourceList} as a {@link ByteBuffer}, pass the {@link ByteBuffer}
     * to the given {@link InputStreamConsumer}, then release the {@link ByteBuffer} after the
     * {@link ByteBufferConsumer} returns, by calling {@link Resource#close()}.
     * 
     * @param byteBufferConsumer
     *            The {@link ByteBufferConsumer}.
     * @param ignoreIOExceptions
     *            if true, any {@link IOException} thrown while trying to load any of the resources will be silently
     *            ignored.
     * @throws IllegalArgumentException
     *             if ignoreExceptions is false, and an {@link IOException} is thrown while trying to load any of
     *             the resources.
     */
    public void forEachByteBuffer(final ByteBufferConsumer byteBufferConsumer, final boolean ignoreIOExceptions) {
        for (final Resource resource : this) {
            try {
                final ByteBuffer byteBuffer = resource.read();
                byteBufferConsumer.accept(resource, byteBuffer);
            } catch (final IOException e) {
                if (!ignoreIOExceptions) {
                    throw new IllegalArgumentException("Could not load resource " + resource, e);
                }
            } finally {
                resource.close();
            }
        }
    }

    /**
     * Read each {@link Resource} in this {@link ResourceList} as a {@link ByteBuffer}, pass the {@link ByteBuffer}
     * to the given {@link InputStreamConsumer}, then release the {@link ByteBuffer} after the
     * {@link ByteBufferConsumer} returns, by calling {@link Resource#close()}.
     * 
     * @param byteBufferConsumer
     *            The {@link ByteBufferConsumer}.
     * @throws IllegalArgumentException
     *             if trying to load any of the resources results in an {@link IOException} being thrown.
     */
    public void forEachByteBuffer(final ByteBufferConsumer byteBufferConsumer) {
        forEachByteBuffer(byteBufferConsumer, /* ignoreIOExceptions = */ false);
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
