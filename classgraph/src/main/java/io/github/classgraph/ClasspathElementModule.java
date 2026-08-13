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
package io.github.classgraph;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import io.github.classgraph.Scanner.ClasspathEntryWorkUnit;
import io.github.classgraph.base.internal.concurrency.SingletonMap;
import io.github.classgraph.base.internal.concurrency.SingletonMap.NewInstanceException;
import io.github.classgraph.base.internal.concurrency.SingletonMap.NullSingletonException;
import io.github.classgraph.base.internal.concurrency.WorkQueue;
import io.github.classgraph.base.internal.recycler.Recycler;
import io.github.classgraph.base.internal.utils.CollectionUtils;
import io.github.classgraph.base.internal.utils.LogNode;
import io.github.classgraph.base.internal.utils.ModuleReaderUtils;
import io.github.classgraph.base.internal.utils.ProxyingInputStream;
import io.github.classgraph.internal.scanspec.ScanSpec;
import io.github.classgraph.internal.scanspec.ScanSpec.ScanSpecPathMatch;
import io.github.classgraph.vfs.internal.slice.reader.ClassfileReader;
import org.jspecify.annotations.Nullable;

/**
 * A module classpath element.
 *
 * @author Luke Hutchison
 */
class ClasspathElementModule extends ClasspathElement {

    /** The module. */
    final ModuleReference moduleReference;

    /**
     * A singleton map from a {@link ModuleReference} to a {@link ModuleReader} recycler for the module.
     */
    private final SingletonMap<ModuleReference, Recycler<ModuleReader, IOException>, IOException> //
    moduleReaderRecyclerMap;

    /** The module reader recycler, or null until {@link #open} has been called. */
    private @Nullable Recycler<ModuleReader, IOException> moduleReaderRecycler;

    /**
     * Get the module reader recycler.
     *
     * @return the module reader recycler
     * @throws NullPointerException
     *             if {@link #open} has not been called, or it failed to open the module
     */
    private Recycler<ModuleReader, IOException> moduleReaderRecycler() {
        return Objects.requireNonNull(moduleReaderRecycler);
    }

    /** All resource paths. */
    private final Set<String> allResourcePaths = new HashSet<>();

    /**
     * True if this module is not being scanned, and was only opened so that the classfiles of individual classes
     * can be read from it (see {@link UnscannedModules}).
     */
    private final boolean isLookupOnly;

    /**
     * A zip/jarfile classpath element.
     *
     * @param moduleReference
     *            the module
     * @param workUnit
     *            the work unit
     * @param moduleReaderRecyclerMap
     *            the map from a module to its module reader recycler
     * @param isLookupOnly
     *            true if the module is not being scanned, and was only opened so that the classfiles of individual
     *            classes can be read from it
     * @param scanSpec
     *            the scan spec
     */
    ClasspathElementModule(final ModuleReference moduleReference,
            final SingletonMap<ModuleReference, Recycler<ModuleReader, IOException>, IOException> //
            moduleReaderRecyclerMap, final ClasspathEntryWorkUnit workUnit, final boolean isLookupOnly,
            final ScanSpec scanSpec) {
        super(workUnit, scanSpec);
        this.moduleReaderRecyclerMap = moduleReaderRecyclerMap;
        this.moduleReference = moduleReference;
        this.isLookupOnly = isLookupOnly;
    }

    @Override
    void open(final @Nullable WorkQueue<ClasspathEntryWorkUnit> workQueueIgnored, final @Nullable LogNode log)
            throws InterruptedException {
        if (!scanSpec.classpathSpec.scanModules) {
            if (log != null) {
                log(classpathElementIdx, "Skipping module, since module scanning is disabled: " + getModuleName(),
                        log);
            }
            skipClasspathElement = true;
            return;
        }
        try {
            moduleReaderRecycler = moduleReaderRecyclerMap.get(moduleReference, log);
        } catch (final IOException | NullSingletonException | NewInstanceException e) {
            if (log != null) {
                log(classpathElementIdx, "Skipping invalid module " + getModuleName() + " : "
                        + (e.getCause() == null ? e : e.getCause()), log);
            }
            skipClasspathElement = true;
        }
    }

    /**
     * Create a new {@link Resource} object for a resource or classfile discovered while scanning paths.
     *
     * @param resourcePath
     *            the resource path
     * @return the resource
     */
    private Resource newResource(final String resourcePath) {
        return new ModuleResource(resourcePath);
    }

    /**
     * A {@link Resource} for an entry in a module. Reading it acquires a {@link ModuleReader} from the classpath
     * element's recycler, and closing it returns that reader to the recycler.
     */
    private class ModuleResource extends Resource {
        /** The path of the resource within the module. */
        private final String resourcePath;

        /** The module reader, or null if no module reader is currently acquired. */
        private @Nullable ModuleReader moduleReader;

        /**
         * Constructor.
         *
         * @param resourcePath
         *            the path of the resource within the module.
         */
        ModuleResource(final String resourcePath) {
            super(ClasspathElementModule.this, /* length unknown */ -1L);
            this.resourcePath = resourcePath;
        }

        @Override
        public String getPath() {
            return resourcePath;
        }

        @Override
        public long getLastModifiedMillis() {
            return 0L; // Unknown
        }

        @Override
        public @Nullable Set<PosixFilePermission> getPosixFilePermissions() {
            return null; // N/A
        }

        @Override
        public ByteBuffer read() throws IOException {
            checkCanOpen();
            try {
                final var reader = moduleReaderRecycler().acquire();
                moduleReader = reader;
                // ModuleReader#read(String name) internally calls:
                // InputStream is = open(name); return ByteBuffer.wrap(is.readAllBytes());
                final var buf = ModuleReaderUtils.read(reader, resourcePath);
                byteBuffer = buf;
                length = buf.remaining();
                return buf;

            } catch (final IOException e) {
                // Leave the resource closed if it could not be read, so that reading it can be tried again, and so
                // that the ModuleReader acquired above is recycled rather than being left checked out
                close();
                throw e;
            } catch (final SecurityException | OutOfMemoryError e) {
                close();
                throw new IOException("Could not open " + this, e);
            }
        }

        @Override
        ClassfileReader openClassfile() throws IOException {
            return new ClassfileReader(open(), this);
        }

        @Override
        public URI getURI() {
            try {
                final var localModuleReader = moduleReaderRecycler().acquire();
                try {
                    return ModuleReaderUtils.find(localModuleReader, resourcePath);
                } finally {
                    moduleReaderRecycler().recycle(localModuleReader);
                }
            } catch (final IOException e) {
                throw new IllegalStateException("Could not get URI for " + this + " : " + e);
            }
        }

        @Override
        public InputStream open() throws IOException {
            checkCanOpen();
            try {
                final Resource thisResource = this;
                final var reader = moduleReaderRecycler().acquire();
                moduleReader = reader;
                inputStream = new ProxyingInputStream(ModuleReaderUtils.open(reader, resourcePath)) {
                    @Override
                    public void close() throws IOException {
                        // Close the wrapped InputStream obtained from moduleReader
                        super.close();
                        try {
                            // Close the Resource, releasing any underlying ByteBuffer and recycling the
                            // moduleReader
                            thisResource.close();
                        } catch (final Exception e) {
                            // Ignore
                        }
                    }
                };
                // Length cannot be obtained from ModuleReader
                length = -1L;
                return inputStream;

            } catch (final IOException e) {
                // Leave the resource closed if it could not be opened, so that opening it can be tried again, and
                // so that the ModuleReader acquired above is recycled rather than being left checked out
                close();
                throw e;
            } catch (final SecurityException e) {
                close();
                throw new IOException("Could not open " + this, e);
            }
        }

        @Override
        public byte[] load() throws IOException {
            try (Resource res = this) { // Close this after use
                final var buf = read(); // Fill byteBuffer
                final byte[] byteArray;
                if (buf.hasArray() && buf.position() == 0 && buf.limit() == buf.capacity()) {
                    byteArray = buf.array();
                } else {
                    byteArray = new byte[buf.remaining()];
                    buf.get(byteArray);
                }
                res.length = byteArray.length;
                return byteArray;
            }
        }

        @Override
        public void close() {
            if (markClosed()) {
                final var reader = moduleReader;
                if (reader != null) {
                    final var buf = byteBuffer;
                    if (buf != null) {
                        // Release any open ByteBuffer
                        reader.release(buf);
                        byteBuffer = null;
                    }
                    // Recycle the (open) ModuleReader instance.
                    moduleReaderRecycler().recycle(reader);
                    // Don't call ModuleReader#close(), leave the ModuleReader open in the recycler. Just set
                    // the ref to null here. The ModuleReader will be closed by ClasspathElementModule#close().
                    moduleReader = null;
                }

                // Close inputStream
                super.close();
            }
        }
    }

    /**
     * Get the {@link Resource} for a given relative path.
     *
     * @param relativePath
     *            The relative path of the {@link Resource} to return.
     * @return The {@link Resource} for the given relative path, or null if relativePath does not exist in this
     *         classpath element.
     */
    @Override
    @Nullable
    Resource getResource(final String relativePath) {
        if (isLookupOnly) {
            // The paths of the resources in a module that is not being scanned were never listed, so ask the module
            // reader whether the module contains this one
            if (skipClasspathElement) {
                return null;
            }
            try {
                final var moduleReader = moduleReaderRecycler().acquire();
                try {
                    return ModuleReaderUtils.contains(moduleReader, relativePath) ? newResource(relativePath)
                            : null;
                } finally {
                    moduleReaderRecycler().recycle(moduleReader);
                }
            } catch (final IOException | SecurityException e) {
                return null;
            }
        }
        return allResourcePaths.contains(relativePath) ? newResource(relativePath) : null;
    }

    /**
     * Scan for package matches within module.
     *
     * @param log
     *            the log node, or null to skip logging
     */
    @Override
    void scanPaths(final @Nullable LogNode log) {
        if (skipClasspathElement) {
            return;
        }
        if (scanned.getAndSet(true)) {
            // Should not happen
            throw new IllegalStateException("Already scanned classpath element " + this);
        }

        final var moduleName = moduleReference.descriptor().name();
        final var subLog = log == null ? null : log(classpathElementIdx, "Scanning module " + moduleName, log);

        // Determine whether this is a modular jar
        final var isModularJar = getModuleName() != null;

        try (var moduleReaderRecycleOnClose //
                = moduleReaderRecycler().acquireRecycleOnClose()) {
            // Look for accepted files in the module.
            final List<String> resourceRelativePaths;
            try {
                resourceRelativePaths = ModuleReaderUtils.list(moduleReaderRecycleOnClose.get(), moduleName,
                        subLog);
            } catch (final IOException | SecurityException e) {
                // A module whose contents cannot be listed is skipped, rather than aborting the whole scan. (A
                // ModuleReader that returns null from list(), in violation of its contract, is handled by
                // ModuleReaderUtils#list(ModuleReader, String, LogNode) instead, which treats the module as empty
                // -- see #887)
                if (subLog != null) {
                    subLog.log("Could not get resource list for module " + moduleName + " -- skipping this module",
                            e);
                }
                return;
            }
            CollectionUtils.sortIfNotEmpty(resourceRelativePaths);

            final var parentDirMatchStatusCache = new ParentDirMatchStatusCache();
            for (final String relativePath : resourceRelativePaths) {
                // From ModuleReader#find(): "If the module reader can determine that the name locates a directory
                // then the resulting URI will end with a slash ('/')." But from the documentation for
                // ModuleReader#list(): "Whether the stream of elements includes names corresponding to directories
                // in the module is module reader specific." We don't have a way of checking if a resource is a
                // directory without trying to open it, unless ModuleReader#list() also decides to put a "/" on the
                // end of resource paths corresponding to directories. Skip directories if they are found, but if
                // they are not able to be skipped, we will have to settle for having some IOExceptions thrown when
                // directories are mistaken for resource files.
                if (relativePath.endsWith("/")) {
                    continue;
                }

                // A versioned path in a module must be a nested versioned section, i.e. a path like
                // "META-INF/versions/{version}/META-INF/versions/{version}/", since META-INF should only ever exist
                // in the module root
                if (isIgnoredVersionedPath(relativePath)) {
                    if (subLog != null) {
                        subLog.log(
                                "Found unexpected nested versioned entry in module -- skipping: " + relativePath);
                    }
                    continue;
                }

                if (isIgnoredDefaultPackageClassfile(isModularJar, relativePath)) {
                    continue;
                }

                // Accept/reject classpath elements based on file resource paths
                if (!checkResourcePathAcceptReject(relativePath, log)) {
                    // The whole classpath element is rejected, so stop scanning the rest of it
                    break;
                }

                final var parentMatchStatus = parentDirMatchStatusCache.getParentMatchStatus(relativePath);
                if (parentMatchStatus == ScanSpecPathMatch.HAS_REJECTED_PATH_PREFIX) {
                    // The parent dir or one of its ancestral dirs is rejected
                    if (subLog != null) {
                        subLog.log("Skipping rejected path: " + relativePath);
                    }
                    continue;
                }

                // Found non-rejected relative path
                if (allResourcePaths.add(relativePath)) {
                    if (isAcceptedResourcePath(relativePath, parentMatchStatus)) {
                        // Add accepted resource
                        addAcceptedResource(newResource(relativePath), parentMatchStatus,
                                /* isClassfileOnly = */ false, subLog);
                    } else if (scanSpec.enableClassInfo && "module-info.class".equals(relativePath)) {
                        // Add module descriptor as an accepted classfile resource, so that it is scanned, but don't
                        // add it to the list of resources in the ScanResult, since it is not in an accepted package
                        // (#352)
                        addAcceptedResource(newResource(relativePath), parentMatchStatus,
                                /* isClassfileOnly = */ true, subLog);
                    }
                }
            }

            // Save last modified time for the module file
            final var moduleFile = getFile();
            if (moduleFile != null) {
                fileToLastModified.put(moduleFile, moduleFile.lastModified());
            }

        } catch (final IOException e) {
            if (subLog != null) {
                subLog.log("Exception opening module " + moduleName, e);
            }
            skipClasspathElement = true;
        }

        finishScanPaths(subLog);
    }

    /**
     * Get the module for this classpath element.
     *
     * @return the module
     */
    ModuleReference getModuleReference() {
        return moduleReference;
    }

    /**
     * Get the module name from the module reference or the module descriptor.
     *
     * @return the module name, or null if the module does not have a name.
     */
    @Override
    public @Nullable String getModuleName() {
        var moduleName = moduleReference.descriptor().name();
        if (moduleName.isEmpty()) {
            moduleName = moduleNameFromModuleDescriptor;
        }
        return moduleName == null || moduleName.isEmpty() ? null : moduleName;
    }

    /**
     * Get the module name from the module reference or the module descriptor.
     *
     * @return the module name, or the empty string if the module does not have a name.
     */
    private String getModuleNameOrEmpty() {
        final var moduleName = getModuleName();
        return moduleName == null ? "" : moduleName;
    }

    @Override
    URI getURI() {
        final var uri = moduleReference.location().orElse(null);
        if (uri == null) {
            // Some modules have no known module location (ModuleReference#location() can return null)
            throw new IllegalStateException("Module " + getModuleName() + " has a null location");
        }
        return uri;
    }

    @Override
    List<URI> getAllURIs() {
        return List.of(getURI());
    }

    @Override
    @Nullable
    File getFile() {
        try {
            final var uri = moduleReference.location().orElse(null);
            // N.B. uri.getScheme() is null for a relative URI, so compare in this order
            if (uri != null && !"jrt".equals(uri.getScheme())) {
                final File file = new File(uri);
                if (file.exists()) {
                    return file;
                }
            }
        } catch (final Exception e) {
            // Invalid "file:" URI
        }
        return null;
    }

    /**
     * Return the module reference as a String.
     *
     * @return the string
     */
    @Override
    public String toString() {
        return moduleReference.toString();
    }

    /**
     * Equals.
     *
     * @param obj
     *            the obj
     * @return true, if successful
     */
    @Override
    public boolean equals(final @Nullable Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof final ClasspathElementModule other)) {
            return false;
        }
        return this.getModuleNameOrEmpty().equals(other.getModuleNameOrEmpty());
    }

    /**
     * Hash code.
     *
     * @return the int
     */
    @Override
    public int hashCode() {
        return getModuleNameOrEmpty().hashCode();
    }
}
