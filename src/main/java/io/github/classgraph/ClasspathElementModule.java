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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleReader;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.classgraph.Scanner.ClasspathEntryWorkUnit;
import nonapi.io.github.classgraph.concurrency.SingletonMap;
import nonapi.io.github.classgraph.concurrency.SingletonMap.NewInstanceException;
import nonapi.io.github.classgraph.concurrency.SingletonMap.NullSingletonException;
import nonapi.io.github.classgraph.concurrency.WorkQueue;
import nonapi.io.github.classgraph.fastzipfilereader.LogicalZipFile;
import nonapi.io.github.classgraph.fileslice.reader.ClassfileReader;
import nonapi.io.github.classgraph.recycler.Recycler;
import nonapi.io.github.classgraph.scanspec.ScanSpec;
import nonapi.io.github.classgraph.scanspec.ScanSpec.ScanSpecPathMatch;
import nonapi.io.github.classgraph.utils.CollectionUtils;
import nonapi.io.github.classgraph.utils.LogNode;
import nonapi.io.github.classgraph.utils.ModuleReaderUtils;
import nonapi.io.github.classgraph.utils.ProxyingInputStream;
import org.jspecify.annotations.Nullable;

/**
 * A module classpath element.
 *
 * @author luke
 */
class ClasspathElementModule extends ClasspathElement {

    /** The module ref. */
    final ModuleRef moduleRef;

    /**
     * A singleton map from a {@link ModuleRef} to a {@link ModuleReader} recycler
     * for the module.
     */
    SingletonMap<ModuleRef, Recycler<ModuleReader, IOException>, IOException> //
    moduleRefToModuleReaderRecyclerMap;

    /** The module reader recycler, or null until {@link #open} has been called. */
    private @Nullable Recycler<ModuleReader, IOException> moduleReaderRecycler;

    /**
     * Get the module reader recycler.
     *
     * @return the module reader recycler
     * @throws NullPointerException if {@link #open} has not been called, or it
     *                              failed to open the module
     */
    private Recycler<ModuleReader, IOException> moduleReaderRecycler() {
        return Objects.requireNonNull(moduleReaderRecycler);
    }

    /** All resource paths. */
    private final Set<String> allResourcePaths = new HashSet<>();

    /**
     * True if this module is not being scanned, and was only opened so that the
     * classfiles of individual classes can be read from it (see
     * {@link UnscannedModules}).
     */
    private final boolean isLookupOnly;

    /**
     * A zip/jarfile classpath element.
     *
     * @param moduleRef                          the module ref
     * @param workUnit                           the work unit
     * @param moduleRefToModuleReaderRecyclerMap the module ref to module reader
     *                                           recycler map
     * @param isLookupOnly                       true if the module is not being
     *                                           scanned, and was only opened so
     *                                           that the classfiles of individual
     *                                           classes can be read from it
     * @param scanSpec                           the scan spec
     */
    ClasspathElementModule(final ModuleRef moduleRef,
            final SingletonMap<ModuleRef, Recycler<ModuleReader, IOException>, IOException> //
            moduleRefToModuleReaderRecyclerMap, final ClasspathEntryWorkUnit workUnit, final boolean isLookupOnly,
            final ScanSpec scanSpec) {
        super(workUnit, scanSpec);
        this.moduleRefToModuleReaderRecyclerMap = moduleRefToModuleReaderRecyclerMap;
        this.moduleRef = moduleRef;
        this.isLookupOnly = isLookupOnly;
    }

    /*
     * (non-Javadoc)
     *
     * @see io.github.classgraph.ClasspathElement#open(
     * nonapi.io.github.classgraph.concurrency.WorkQueue,
     * nonapi.io.github.classgraph.utils.LogNode)
     */
    @Override
    void open(final @Nullable WorkQueue<ClasspathEntryWorkUnit> workQueueIgnored, final @Nullable LogNode log)
            throws InterruptedException {
        if (!scanSpec.scanModules) {
            if (log != null) {
                log(classpathElementIdx, "Skipping module, since module scanning is disabled: " + getModuleName(), log);
            }
            skipClasspathElement = true;
            return;
        }
        try {
            moduleReaderRecycler = moduleRefToModuleReaderRecyclerMap.get(moduleRef, log);
        } catch (final IOException | NullSingletonException | NewInstanceException e) {
            if (log != null) {
                log(classpathElementIdx, "Skipping invalid module " + getModuleName() + " : "
                        + (e.getCause() == null ? e : e.getCause()), log);
            }
            skipClasspathElement = true;
            return;
        }
    }

    /**
     * Create a new {@link Resource} object for a resource or classfile discovered
     * while scanning paths.
     *
     * @param resourcePath the resource path
     * @return the resource
     */
    private Resource newResource(final String resourcePath) {
        return new Resource(this, /* length unknown */ -1L) {
            /** The module reader, or null if no module reader is currently acquired. */
            private @Nullable ModuleReader moduleReader;

            /** True if the resource is open. */
            private final AtomicBoolean isOpen = new AtomicBoolean();

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

            protected void checkCanOpen() {
                if (skipClasspathElement) {
                    // Shouldn't happen
                    throw new IllegalStateException("Classpath element could not be opened");
                }
                if (isOpen.getAndSet(true)) {
                    throw new IllegalStateException(
                            "Resource is already open -- cannot open it again without first calling close()");
                }
                if (scanResult != null && scanResult.isClosed()) {
                    throw new IllegalStateException("Cannot open a resource after the ScanResult is closed");
                }
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
                    throw new RuntimeException(e);
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
                                // Close the Resource, releasing any underlying ByteBuffer and recycling
                                // the moduleReader
                                thisResource.close();
                            } catch (final Exception e) {
                                // Ignore
                            }
                        }
                    };
                    // Length cannot be obtained from ModuleReader
                    length = -1L;
                    return inputStream;

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
                if (isOpen.getAndSet(false)) {
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
                        // Don't call ModuleReader#close(), leave the ModuleReader open in the recycler.
                        // Just set the ref to null here. The ModuleReader will be closed by
                        // ClasspathElementModule#close().
                        moduleReader = null;
                    }

                    // Close inputStream
                    super.close();
                }
            }
        };
    }

    /**
     * Get the {@link Resource} for a given relative path.
     *
     * @param relativePath The relative path of the {@link Resource} to return.
     * @return The {@link Resource} for the given relative path, or null if
     *         relativePath does not exist in this classpath element.
     */
    @Override
    @Nullable
    Resource getResource(final String relativePath) {
        if (isLookupOnly) {
            // The paths of the resources in a module that is not being scanned were never
            // listed, so ask the module reader whether the module contains this one
            if (skipClasspathElement) {
                return null;
            }
            try {
                final var moduleReader = moduleReaderRecycler().acquire();
                try {
                    return ModuleReaderUtils.contains(moduleReader, relativePath) ? newResource(relativePath) : null;
                } finally {
                    moduleReaderRecycler().recycle(moduleReader);
                }
            } catch (final IOException | SecurityException | IllegalArgumentException e) {
                return null;
            }
        }
        return allResourcePaths.contains(relativePath) ? newResource(relativePath) : null;
    }

    /**
     * Scan for package matches within module.
     *
     * @param log the log node, or null to skip logging
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

        final var subLog = log == null ? null : log(classpathElementIdx, "Scanning module " + moduleRef.getName(), log);

        // Determine whether this is a modular jar
        final var isModularJar = getModuleName() != null;

        try (var moduleReaderRecycleOnClose //
                = moduleReaderRecycler().acquireRecycleOnClose()) {
            // Look for accepted files in the module.
            final List<String> resourceRelativePaths;
            try {
                resourceRelativePaths = ModuleReaderUtils.list(moduleReaderRecycleOnClose.get(), moduleRef.getName(),
                        subLog);
            } catch (final SecurityException | IllegalArgumentException e) {
                // A module whose contents cannot be listed is skipped, rather than aborting the
                // whole scan.
                // (A ModuleReader that returns null from list(), in violation of its contract,
                // is handled by
                // ModuleReaderUtils#list(ModuleReader, String, LogNode) instead, which treats
                // the module as empty -- see #887)
                if (subLog != null) {
                    subLog.log("Could not get resource list for module " + moduleRef.getName()
                            + " -- skipping this module", e);
                }
                return;
            }
            CollectionUtils.sortIfNotEmpty(resourceRelativePaths);

            String prevParentRelativePath = null;
            ScanSpecPathMatch prevParentMatchStatus = null;
            for (final String relativePath : resourceRelativePaths) {
                // From ModuleReader#find(): "If the module reader can determine that the name
                // locates a
                // directory then the resulting URI will end with a slash ('/')." But from the
                // documentation
                // for ModuleReader#list(): "Whether the stream of elements includes names
                // corresponding to
                // directories in the module is module reader specific." We don't have a way of
                // checking if
                // a resource is a directory without trying to open it, unless
                // ModuleReader#list() also decides
                // to put a "/" on the end of resource paths corresponding to directories. Skip
                // directories if
                // they are found, but if they are not able to be skipped, we will have to
                // settle for having
                // some IOExceptions thrown when directories are mistaken for resource files.
                if (relativePath.endsWith("/")) {
                    continue;
                }

                // Paths in modules should never start with "META-INF/versions/{version}/",
                // because the module
                // system should already strip these prefixes away. If they are found, then the
                // jarfile must
                // contain a path like
                // "META-INF/versions/{version}/META-INF/versions/{version}/", which cannot
                // be valid (META-INF should only ever exist in the module root), and the nested
                // versioned section
                // should be ignored.
                if (!scanSpec.enableMultiReleaseVersions
                        && relativePath.startsWith(LogicalZipFile.MULTI_RELEASE_PATH_PREFIX)) {
                    if (subLog != null) {
                        subLog.log("Found unexpected nested versioned entry in module -- skipping: " + relativePath);
                    }
                    continue;
                }

                // If this is a modular jar, ignore all classfiles other than
                // "module-info.class" in the
                // default package, since these are disallowed.
                if (isModularJar && relativePath.indexOf('/') < 0 && relativePath.endsWith(".class")
                        && !"module-info.class".equals(relativePath)) {
                    continue;
                }

                // Accept/reject classpath elements based on file resource paths
                if (!checkResourcePathAcceptReject(relativePath, log)) {
                    continue;
                }

                // Get match status of the parent directory of this resource's relative path (or
                // reuse the last
                // match status for speed, if the directory name hasn't changed).
                final var lastSlashIdx = relativePath.lastIndexOf('/');
                final var parentRelativePath = lastSlashIdx < 0 ? "/" : relativePath.substring(0, lastSlashIdx + 1);
                final var parentRelativePathChanged = !parentRelativePath.equals(prevParentRelativePath);
                final var parentMatchStatus = //
                        prevParentRelativePath == null || parentRelativePathChanged
                                ? scanSpec.dirAcceptMatchStatus(parentRelativePath)
                                // prevParentRelativePath is null on the first iteration, so
                                // prevParentMatchStatus has always been set by the time it is read
                                : Objects.requireNonNull(prevParentMatchStatus);
                prevParentRelativePath = parentRelativePath;
                prevParentMatchStatus = parentMatchStatus;

                if (parentMatchStatus == ScanSpecPathMatch.HAS_REJECTED_PATH_PREFIX) {
                    // The parent dir or one of its ancestral dirs is rejected
                    if (subLog != null) {
                        subLog.log("Skipping rejected path: " + relativePath);
                    }
                    continue;
                }

                // Found non-rejected relative path
                if (allResourcePaths.add(relativePath)) {
                    // If resource is accepted
                    if (parentMatchStatus == ScanSpecPathMatch.HAS_ACCEPTED_PATH_PREFIX
                            || parentMatchStatus == ScanSpecPathMatch.AT_ACCEPTED_PATH
                            || (parentMatchStatus == ScanSpecPathMatch.AT_ACCEPTED_CLASS_PACKAGE
                                    && scanSpec.classfileIsSpecificallyAccepted(relativePath))) {
                        // Add accepted resource
                        addAcceptedResource(newResource(relativePath), parentMatchStatus, /* isClassfileOnly = */ false,
                                subLog);
                    } else if (scanSpec.enableClassInfo && "module-info.class".equals(relativePath)) {
                        // Add module descriptor as an accepted classfile resource, so that it is
                        // scanned,
                        // but don't add it to the list of resources in the ScanResult, since it is not
                        // in an accepted package (#352)
                        addAcceptedResource(newResource(relativePath), parentMatchStatus, /* isClassfileOnly = */ true,
                                subLog);
                    }
                }
            }

            // Save last modified time for the module file
            final var moduleFile = moduleRef.getLocationFile();
            if (moduleFile != null && moduleFile.exists()) {
                fileToLastModified.put(moduleFile, moduleFile.lastModified());
            }

        } catch (final IOException e) {
            if (subLog != null) {
                subLog.log("Exception opening module " + moduleRef.getName(), e);
            }
            skipClasspathElement = true;
        }

        finishScanPaths(subLog);
    }

    /**
     * Get the ModuleRef for this classpath element.
     *
     * @return the module ref
     */
    ModuleRef getModuleRef() {
        return moduleRef;
    }

    /**
     * Get the module name from the module reference or the module descriptor.
     *
     * @return the module name, or null if the module does not have a name.
     */
    @Override
    public @Nullable String getModuleName() {
        var moduleName = moduleRef.getName();
        if (moduleName == null || moduleName.isEmpty()) {
            moduleName = moduleNameFromModuleDescriptor;
        }
        return moduleName == null || moduleName.isEmpty() ? null : moduleName;
    }

    /**
     * Get the module name from the module reference or the module descriptor.
     *
     * @return the module name, or the empty string if the module does not have a
     *         name.
     */
    private String getModuleNameOrEmpty() {
        final var moduleName = getModuleName();
        return moduleName == null ? "" : moduleName;
    }

    /*
     * (non-Javadoc)
     *
     * @see io.github.classgraph.ClasspathElement#getURI()
     */
    @Override
    URI getURI() {
        final var uri = moduleRef.getLocation();
        if (uri == null) {
            // Some modules have no known module location (ModuleReference#location() can
            // return null)
            throw new IllegalStateException("Module " + getModuleName() + " has a null location");
        }
        return uri;
    }

    @Override
    List<URI> getAllURIs() {
        return List.of(getURI());
    }

    /*
     * (non-Javadoc)
     *
     * @see io.github.classgraph.ClasspathElement#getFile()
     */
    @Override
    @Nullable
    File getFile() {
        try {
            final var uri = moduleRef.getLocation();
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
        return moduleRef.toString();
    }

    /**
     * Equals.
     *
     * @param obj the obj
     * @return true, if successful
     */
    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#equals(java.lang.Object)
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
    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        return getModuleNameOrEmpty().hashCode();
    }
}
