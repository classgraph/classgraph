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
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.classgraph.Scanner.ClasspathEntryWorkUnit;
import nonapi.io.github.classgraph.ScanSpec;
import nonapi.io.github.classgraph.ScanSpec.ScanSpecPathMatch;
import nonapi.io.github.classgraph.concurrency.SingletonMap.NullSingletonException;
import nonapi.io.github.classgraph.concurrency.WorkQueue;
import nonapi.io.github.classgraph.fastzipfilereader.NestedJarHandler;
import nonapi.io.github.classgraph.recycler.RecycleOnClose;
import nonapi.io.github.classgraph.recycler.Recycler;
import nonapi.io.github.classgraph.utils.InputStreamOrByteBufferAdapter;
import nonapi.io.github.classgraph.utils.LogNode;
import nonapi.io.github.classgraph.utils.URLPathEncoder;

/** A module classpath element. */
class ClasspathElementModule extends ClasspathElement {

    /** The module ref. */
    private final ModuleRef moduleRef;

    /** The nested jar handler. */
    private final NestedJarHandler nestedJarHandler;

    /** The module reader proxy recycler. */
    private Recycler<ModuleReaderProxy, IOException> moduleReaderProxyRecycler;

    /** All resource paths. */
    private final Set<String> allResourcePaths = new HashSet<>();

    /**
     * A zip/jarfile classpath element.
     *
     * @param moduleRef
     *            the module ref
     * @param classLoader
     *            the classloader
     * @param nestedJarHandler
     *            the nested jar handler
     * @param scanSpec
     *            the scan spec
     */
    ClasspathElementModule(final ModuleRef moduleRef, final ClassLoader classLoader,
            final NestedJarHandler nestedJarHandler, final ScanSpec scanSpec) {
        super(classLoader, scanSpec);
        this.moduleRef = moduleRef;
        this.nestedJarHandler = nestedJarHandler;
    }

    /* (non-Javadoc)
     * @see io.github.classgraph.ClasspathElement#open(
     * nonapi.io.github.classgraph.concurrency.WorkQueue, nonapi.io.github.classgraph.utils.LogNode)
     */
    @Override
    void open(final WorkQueue<ClasspathEntryWorkUnit> workQueueIgnored, final LogNode log)
            throws InterruptedException {
        if (!scanSpec.scanModules) {
            if (log != null) {
                log.log("Skipping module, since module scanning is disabled: " + getModuleName());
            }
            skipClasspathElement = true;
            return;
        }
        try {
            moduleReaderProxyRecycler = nestedJarHandler.moduleRefToModuleReaderProxyRecyclerMap.get(moduleRef,
                    /* ignored */ null);
        } catch (final IOException | NullSingletonException e) {
            if (log != null) {
                log.log("Skipping invalid module " + getModuleName() + " : " + e);
            }
            skipClasspathElement = true;
            return;
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
        return new Resource() {
            /** The module reader proxy. */
            private ModuleReaderProxy moduleReaderProxy;

            @Override
            public String getPath() {
                return resourcePath;
            }

            @Override
            public String getPathRelativeToClasspathElement() {
                return resourcePath;
            }

            @Override
            public URL getURL() {
                final URL locationURL = getClasspathElementURL();
                final String locationURLStr = locationURL.toString();
                // Check if this is a directory-based module (location URI will end in "/")
                final boolean isDir = locationURLStr.endsWith("/");
                try {
                    // Call normalizeURLPath, which will prepend "jar:" if the URL path contains "!"
                    return new URL(
                            URLPathEncoder.normalizeURLPath(locationURLStr + (isDir ? "" : "!/") + resourcePath));
                } catch (final MalformedURLException e) {
                    throw new IllegalArgumentException("Could not form URL for module location: "
                            + moduleRef.getLocation() + " ; path: " + resourcePath + " : " + e);
                }
            }

            @Override
            public URL getClasspathElementURL() {
                final URI location = moduleRef.getLocation();
                if (location == null) {
                    // Some modules have no known module location (ModuleReference#location() can return null)
                    throw new IllegalArgumentException("Could not create URL for module " + getModuleName()
                            + " ; path: " + resourcePath + " -- module location is null");
                } else if (location.getScheme().equals("jrt")) {
                    // Currently URL cannot handle the "jrt:" scheme, used by system modules.
                    // Need to add a getURI() method to support these.
                    throw new IllegalArgumentException("Could not create URL for module " + getModuleName()
                            + " ; path: " + resourcePath + " -- module location is a \"jrt:\" URI");
                }
                try {
                    return location.toURL();
                } catch (final MalformedURLException e) {
                    throw new IllegalArgumentException(
                            "Could not form URL for module classpath element: " + moduleRef.getLocationStr());
                }
            }

            @Override
            public File getClasspathElementFile() {
                return null;
            }

            @Override
            public ModuleRef getModuleRef() {
                return moduleRef;
            }

            @Override
            public synchronized ByteBuffer read() throws IOException {
                if (skipClasspathElement) {
                    // Shouldn't happen
                    throw new IOException("Module could not be opened");
                }
                markAsOpen();
                try {
                    moduleReaderProxy = moduleReaderProxyRecycler.acquire();
                    // ModuleReader#read(String name) internally calls:
                    // InputStream is = open(name); return ByteBuffer.wrap(is.readAllBytes());
                    byteBuffer = moduleReaderProxy.read(resourcePath);
                    length = byteBuffer.remaining();
                    return byteBuffer;

                } catch (final SecurityException | OutOfMemoryError e) {
                    close();
                    throw new IOException("Could not open " + this, e);
                }
            }

            @Override
            synchronized InputStreamOrByteBufferAdapter openOrRead() throws IOException {
                return new InputStreamOrByteBufferAdapter(open());
            }

            @Override
            public synchronized InputStream open() throws IOException {
                if (skipClasspathElement) {
                    // Shouldn't happen
                    throw new IOException("Module could not be opened");
                }
                markAsOpen();
                try {
                    moduleReaderProxy = moduleReaderProxyRecycler.acquire();
                    inputStream = new InputStreamResourceCloser(this, moduleReaderProxy.open(resourcePath));
                    // Length cannot be obtained from ModuleReader
                    length = -1L;
                    return inputStream;

                } catch (final SecurityException e) {
                    close();
                    throw new IOException("Could not open " + this, e);
                }
            }

            @Override
            public synchronized byte[] load() throws IOException {
                try {
                    read();
                    final byte[] byteArray = byteBufferToByteArray();
                    length = byteArray.length;
                    return byteArray;
                } finally {
                    close();
                }
            }

            @Override
            public synchronized void close() {
                super.close(); // Close inputStream
                if (byteBuffer != null) {
                    if (moduleReaderProxy != null) {
                        // Release any open ByteBuffer
                        moduleReaderProxy.release(byteBuffer);
                    }
                    byteBuffer = null;
                }
                if (moduleReaderProxy != null) {
                    // Recycle the (open) ModuleReaderProxy instance.
                    moduleReaderProxyRecycler.recycle(moduleReaderProxy);
                    // Don't call ModuleReaderProxy#close(), leave the ModuleReaderProxy open in the recycler.
                    // Just set the ref to null here. The ModuleReaderProxy will be closed by
                    // ClasspathElementModule#close().
                    moduleReaderProxy = null;
                }
                markAsClosed();
            }
        };
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
    Resource getResource(final String relativePath) {
        return allResourcePaths.contains(relativePath) ? newResource(relativePath) : null;
    }

    /**
     * Scan for package matches within module.
     *
     * @param log
     *            the log
     */
    @Override
    void scanPaths(final LogNode log) {
        if (skipClasspathElement) {
            return;
        }
        if (scanned.getAndSet(true)) {
            // Should not happen
            throw new IllegalArgumentException("Already scanned classpath element " + toString());
        }

        final String moduleLocationStr = moduleRef.getLocationStr();
        final LogNode subLog = log == null ? null
                : log.log(moduleLocationStr, "Scanning module " + moduleRef.getName());

        try (RecycleOnClose<ModuleReaderProxy, IOException> moduleReaderProxyRecycleOnClose //
                = moduleReaderProxyRecycler.acquireRecycleOnClose()) {
            // Look for whitelisted files in the module.
            List<String> resourceRelativePaths;
            try {
                resourceRelativePaths = moduleReaderProxyRecycleOnClose.get().list();
            } catch (final SecurityException e) {
                if (subLog != null) {
                    subLog.log("Could not get resource list for module " + moduleRef.getName(), e);
                }
                return;
            }
            Collections.sort(resourceRelativePaths);

            String prevParentRelativePath = null;
            ScanSpecPathMatch prevParentMatchStatus = null;
            for (final String relativePath : resourceRelativePaths) {
                // From ModuleReader#find(): "If the module reader can determine that the name locates a
                // directory then the resulting URI will end with a slash ('/')."  But from the documentation
                // for ModuleReader#list(): "Whether the stream of elements includes names corresponding to
                // directories in the module is module reader specific."  We don't have a way of checking if
                // a resource is a directory without trying to open it, unless ModuleReader#list() also decides
                // to put a "/" on the end of resource paths corresponding to directories. Skip directories if
                // they are found, but if they are not able to be skipped, we will have to settle for having
                // some IOExceptions thrown when directories are mistaken for resource files.
                if (relativePath.endsWith("/")) {
                    continue;
                }

                // Whitelist/blacklist classpath elements based on file resource paths
                checkResourcePathWhiteBlackList(relativePath, log);
                if (skipClasspathElement) {
                    return;
                }

                // Get match status of the parent directory of this resource's relative path (or reuse the last
                // match status for speed, if the directory name hasn't changed).
                final int lastSlashIdx = relativePath.lastIndexOf('/');
                final String parentRelativePath = lastSlashIdx < 0 ? "/"
                        : relativePath.substring(0, lastSlashIdx + 1);
                final boolean parentRelativePathChanged = !parentRelativePath.equals(prevParentRelativePath);
                final ScanSpecPathMatch parentMatchStatus = //
                        prevParentRelativePath == null || parentRelativePathChanged
                                ? scanSpec.dirWhitelistMatchStatus(parentRelativePath)
                                : prevParentMatchStatus;
                prevParentRelativePath = parentRelativePath;
                prevParentMatchStatus = parentMatchStatus;

                if (parentMatchStatus == ScanSpecPathMatch.HAS_BLACKLISTED_PATH_PREFIX) {
                    // The parent dir or one of its ancestral dirs is blacklisted
                    if (subLog != null) {
                        subLog.log("Skipping blacklisted path: " + relativePath);
                    }
                    continue;
                }

                // Found non-blacklisted relative path
                if (allResourcePaths.add(relativePath)
                        // If resource is whitelisted
                        && (parentMatchStatus == ScanSpecPathMatch.HAS_WHITELISTED_PATH_PREFIX
                                || parentMatchStatus == ScanSpecPathMatch.AT_WHITELISTED_PATH
                                || (parentMatchStatus == ScanSpecPathMatch.AT_WHITELISTED_CLASS_PACKAGE
                                        && scanSpec.classfileIsSpecificallyWhitelisted(relativePath))
                                || (scanSpec.enableClassInfo && relativePath.equals("module-info.class")))) {
                    // Add whitelisted resource
                    final Resource resource = newResource(relativePath);
                    addWhitelistedResource(resource, parentMatchStatus, subLog);
                }
            }

            // Save last modified time for the module file
            final File moduleFile = moduleRef.getLocationFile();
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
    public String getModuleName() {
        String moduleName = moduleRef.getName();
        if (moduleName == null || moduleName.isEmpty()) {
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
        final String moduleName = getModuleName();
        return moduleName == null ? "" : moduleName;
    }

    /* (non-Javadoc)
     * @see io.github.classgraph.ClasspathElement#getURI()
     */
    @Override
    URI getURI() {
        return moduleRef.getLocation();
    }

    /* (non-Javadoc)
     * @see io.github.classgraph.ClasspathElement#getFile()
     */
    @Override
    File getFile() {
        final URI location = moduleRef.getLocation();
        if (location != null && "file".equals(location.getScheme())) {
            return new File(location);
        } else {
            return null;
        }
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

    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(final Object o) {
        if (o == this) {
            return true;
        } else if (!(o instanceof ClasspathElementModule)) {
            return false;
        }
        final ClasspathElementModule other = (ClasspathElementModule) o;
        return this.getModuleNameOrEmpty().equals(other.getModuleNameOrEmpty());
    }

    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        return getModuleNameOrEmpty().hashCode();
    }
}
