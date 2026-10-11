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
import java.lang.module.ModuleReference;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import io.github.classgraph.ScanSpec.ScanSpecPathMatch;
import io.github.classgraph.Scanner.ClasspathEntryWorkUnit;
import io.github.classgraph.base.LogNode;
import io.github.classgraph.classpath.ClassLoaderHandler;
import io.github.classgraph.vfs.Vfs;
import io.github.classgraph.vfs.VfsEntry;
import io.github.classgraph.vfs.VfsRoot;
import io.github.classgraph.vfs.VfsVisitor;
import org.jspecify.annotations.Nullable;

/**
 * A module classpath element.
 *
 * @author Luke Hutchison
 */
final class ClasspathElementModule extends ClasspathElement {

    /** The module. */
    final ModuleReference moduleReference;

    /** The virtual filesystem that the module is enumerated and read through. */
    private final Vfs vfs;

    /** The module, as a root of the virtual filesystem, or null until {@link #open} has been called. */
    private @Nullable VfsRoot moduleRoot;

    /**
     * Get the module as a root of the virtual filesystem.
     *
     * @return the root.
     * @throws NullPointerException
     *             if {@link #open} has not been called, or it failed to open the module
     */
    private VfsRoot moduleRoot() {
        return Objects.requireNonNull(moduleRoot);
    }

    /** All resource paths. */
    private final Set<String> allResourcePaths = new HashSet<>();

    /**
     * True if this module is not being scanned, and was only opened so that the classfiles of individual classes
     * can be read from it (see {@link UnscannedModules}).
     */
    private final boolean isLookupOnly;

    /**
     * A module classpath element.
     *
     * @param moduleReference
     *            the module
     * @param vfs
     *            the virtual filesystem to read the module through
     * @param classLoaderStr
     *            the string form of the classloader to record for the module, or null if there is none
     * @param isLookupOnly
     *            true if the module is not being scanned, and was only opened so that the classfiles of individual
     *            classes can be read from it
     * @param scanSpec
     *            the scan spec
     */
    ClasspathElementModule(final ModuleReference moduleReference, final Vfs vfs,
            final @Nullable String classLoaderStr, final boolean isLookupOnly, final ScanSpec scanSpec) {
        // A module has no package roots or lib dirs
        super(classLoaderStr, /* packageRootPrefix = */ "", ClassLoaderHandler.NO_PACKAGE_ROOT_PREFIXES,
                ClassLoaderHandler.NO_LIB_DIR_PREFIXES, vfs, scanSpec);
        this.vfs = vfs;
        this.moduleReference = moduleReference;
        this.isLookupOnly = isLookupOnly;
    }

    @Override
    void open(final @Nullable WorkQueue<ClasspathEntryWorkUnit> workQueueIgnored, final @Nullable LogNode log) {
        try {
            moduleRoot = vfs.open(moduleReference);
        } catch (final IOException e) {
            // The module itself is not opened until it is listed or read, so this only fails if the virtual
            // filesystem has been closed
            if (log != null) {
                log.log("Skipping module " + getModuleName() + " : " + e);
            }
            skipClasspathElement = true;
        }
    }

    /**
     * Create a new {@link Resource} object for a resource or classfile discovered while scanning paths.
     *
     * @param entry
     *            the resource, as an entry in the virtual filesystem
     * @return the resource
     */
    private Resource newResource(final VfsEntry entry) {
        return new Resource(this, entry, entry.getPathFromRoot());
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
        if (skipClasspathElement) {
            return null;
        }
        // The paths of the resources in a module that is not being scanned were never listed, so for such a module
        // the module itself has to be asked whether it contains this resource
        if (!isLookupOnly && !allResourcePaths.contains(relativePath)) {
            return null;
        }
        try {
            final var entry = moduleRoot().getEntry(relativePath);
            return entry == null ? null : newResource(entry);
        } catch (final IOException e) {
            return null;
        }
    }

    /**
     * Check whether a path is that of a classfile in the default (unnamed) package.
     *
     * <p>
     * A module cannot contain classes in the default package, and {@code module-info.class} is the only classfile
     * allowed in the root of a module, so any other classfile found there cannot be loaded, and is ignored. This
     * applies only to a module on the module path: a jarfile or directory on the classpath is in the unnamed
     * module, even if it has a {@code module-info.class} or an {@code Automatic-Module-Name} manifest attribute,
     * and the JVM loads the classes in its default package.
     *
     * @param relativePath
     *            the path of an entry, relative to the root of the module.
     * @return true if the entry is a classfile in the default package, and should be ignored.
     */
    private static boolean isDefaultPackageClassfile(final String relativePath) {
        return relativePath.indexOf('/') < 0 && ClassNames.isClassfilePath(relativePath)
                && !"module-info.class".equals(relativePath);
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

        try {
            // Let the virtual filesystem enumerate the module, and decide here which of the entries it offers are
            // wanted. The directory the entries are in is offered before the entries themselves, so the accept /
            // reject status of a directory only has to be worked out once for all the entries in it.
            moduleRoot().walk(new VfsVisitor() {
                /** The accept/reject match status of the directory currently being walked. */
                private ScanSpecPathMatch parentMatchStatus = ScanSpecPathMatch.NOT_WITHIN_ACCEPTED_PATH;

                @Override
                public boolean enterDirectory(final String dirName) {
                    parentMatchStatus = scanSpec.dirAcceptMatchStatus(dirName);
                    // Never skip a directory, even a rejected one: every entry still has to be offered to
                    // checkResourcePathAcceptReject below, which is what records whether this module contains a
                    // rejected or a specifically accepted classpath element resource path
                    return true;
                }

                @Override
                public boolean visitEntry(final VfsEntry entry) {
                    final var relativePath = entry.getPathFromRoot();

                    // A versioned path in a module must be a nested versioned section, i.e. a path like
                    // "META-INF/versions/{version}/META-INF/versions/{version}/", since META-INF should only ever
                    // exist in the module root
                    if (isIgnoredVersionedPath(relativePath)) {
                        if (subLog != null) {
                            subLog.log("Found unexpected nested versioned entry in module -- skipping: "
                                    + relativePath);
                        }
                        return true;
                    }

                    // Accept/reject classpath elements based on file resource paths, including the paths of files
                    // that are not themselves scanned
                    if (!checkResourcePathAcceptReject(relativePath, subLog)) {
                        // The whole classpath element is rejected, so stop scanning the rest of it
                        return false;
                    }

                    if (isDefaultPackageClassfile(relativePath)) {
                        return true;
                    }

                    if (parentMatchStatus == ScanSpecPathMatch.HAS_REJECTED_PATH_PREFIX && subLog != null) {
                        // The parent dir or one of its ancestral dirs is rejected, so the entry is not accepted. It
                        // is still recorded below, so that it can be found by getResource(String), which is how the
                        // class graph is extended upwards through classes that the scan did not accept -- exactly
                        // as it is for a directory classpath element, whose lookup reads the filesystem and so
                        // reaches rejected paths too
                        subLog.log("Not accepting rejected path: " + relativePath);
                    }

                    if (allResourcePaths.add(relativePath)) {
                        if (isAcceptedResourcePath(relativePath, parentMatchStatus)) {
                            // Add accepted resource
                            addAcceptedResource(newResource(entry), parentMatchStatus,
                                    /* isClassfileOnly = */ false, subLog);
                        } else if (scanSpec.enableClassInfo && "module-info.class".equals(relativePath)) {
                            // Add module descriptor as an accepted classfile resource, so that it is scanned, but
                            // don't add it to the list of resources in the ScanResult, since it is not in an
                            // accepted package (#352)
                            addAcceptedResource(newResource(entry), parentMatchStatus, /* isClassfileOnly = */ true,
                                    subLog);
                        }
                    }
                    return true;
                }
            }, subLog);

            // Save last modified time for the module file
            final var moduleFile = getFile();
            if (moduleFile != null) {
                fileToLastModified.put(moduleFile, moduleFile.lastModified());
            }

        } catch (final IOException e) {
            // A module whose contents cannot be listed is skipped, rather than aborting the whole scan. (A
            // ModuleReader that returns null from list(), in violation of its contract, is handled by
            // ModuleReaderUtils#list(ModuleReader, String, LogNode) instead, which treats the module as empty
            // -- see #887)
            if (subLog != null) {
                subLog.log("Could not read module " + moduleName + " -- skipping this module", e);
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
     * Get the module name from the module descriptor. A module descriptor always has a name, since the module
     * system rejects a descriptor with an empty name.
     *
     * @return the module name.
     */
    @Override
    public String getModuleName() {
        return moduleReference.descriptor().name();
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
        } catch (final IllegalArgumentException | SecurityException e) {
            // The location is not a "file:" URI, or is one that does not name a file, such as one with a query,
            // or a security manager does not let the file be checked
        }
        return null;
    }

    /**
     * Return the module reference as a String.
     *
     * @return the module reference as a String
     */
    @Override
    public String toString() {
        return moduleReference.toString();
    }

    /**
     * Two module classpath elements are equal if their modules have the same name.
     *
     * @param obj
     *            the object to compare to
     * @return true if {@code obj} is a module classpath element for a module of the same name
     */
    @Override
    public boolean equals(final @Nullable Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof final ClasspathElementModule other)) {
            return false;
        }
        return this.getModuleName().equals(other.getModuleName());
    }

    /**
     * Hash the module name, to match {@link #equals(Object)}.
     *
     * @return the hash code of the module name
     */
    @Override
    public int hashCode() {
        return getModuleName().hashCode();
    }
}
