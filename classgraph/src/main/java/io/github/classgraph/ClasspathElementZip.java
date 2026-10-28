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
import java.io.IOError;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import io.github.classgraph.ScanSpec.ScanSpecPathMatch;
import io.github.classgraph.Scanner.ClasspathEntryWorkUnit;
import io.github.classgraph.base.LogNode;
import io.github.classgraph.base.internal.path.FastPathResolver;
import io.github.classgraph.base.internal.path.FileUtils;
import io.github.classgraph.base.internal.path.PathSyntax;
import io.github.classgraph.base.internal.path.URLPaths;
import io.github.classgraph.classpath.internal.ClasspathExpander;
import io.github.classgraph.classpath.internal.ClasspathExpander.ChildEntry;
import io.github.classgraph.vfs.ArchiveRoot;
import io.github.classgraph.vfs.Vfs;
import io.github.classgraph.vfs.VfsEntry;
import io.github.classgraph.vfs.VfsRoot;
import io.github.classgraph.vfs.VfsVisitor;

/** A zip/jarfile classpath element. */
final class ClasspathElementZip extends ClasspathElement {
    /**
     * The {@link String} representation of the path string, {@link URL}, {@link URI}, or {@link Path} for this
     * zipfile.
     */
    private final String rawPath;
    /**
     * The normalized path of the jarfile, "!/"-separated if nested, excluding any package root.
     */
    private String zipFilePath;
    /** The virtual filesystem that the jarfile is enumerated and read through. */
    private final Vfs vfs;
    /**
     * The jarfile, as a root of the virtual filesystem, or null until {@link #open} has been called (or if the
     * classpath element could not be opened).
     */
    @Nullable
    VfsRoot vfsRoot;
    /** The automatic module name, derived from the jarfile filename. */
    private @Nullable String derivedAutomaticModuleName;

    /**
     * A jarfile classpath element.
     *
     * @param workUnit
     *            the work unit
     * @param rawPathObj
     *            the normalized classpath entry of the work unit (a {@link String} path, {@link Path}, {@link URL}
     *            or {@link URI})
     * @param vfs
     *            the virtual filesystem to enumerate and read the jarfile through
     * @param scanSpec
     *            the scan spec
     */
    ClasspathElementZip(final ClasspathEntryWorkUnit workUnit, final Object rawPathObj, final Vfs vfs,
            final ScanSpec scanSpec) {
        super(workUnit.classLoaderStr(), workUnit.packageRootPrefix(), workUnit.packageRootPrefixes(),
                workUnit.libDirPrefixes(), vfs, scanSpec);

        // Convert the raw path object (Path, URL, or URI) to a string. Any required URL/URI parsing is done when
        // the Vfs opens the path.
        String rawPath = null;
        if (rawPathObj instanceof final Path path) {
            // Path.toString does not include URI scheme => turn into a URI so that toString works
            try {
                rawPath = path.toUri().toString();
            } catch (final IOError | SecurityException e) {
                // Fall through
            }
        }
        if (rawPath == null) {
            rawPath = rawPathObj.toString();
        }
        this.rawPath = rawPath;
        this.zipFilePath = rawPath; // May change when open() is called
        this.vfs = vfs;
    }

    @Override
    void open(final WorkQueue<ClasspathEntryWorkUnit> workQueue, final @Nullable LogNode log) {
        if (!scanSpec.scanJars) {
            if (log != null) {
                log.log("Skipping classpath element, since jar scanning is disabled: " + rawPath);
            }
            skipClasspathElement = true;
            return;
        }
        final var subLog = log == null ? null : log.log("Opening jar: " + rawPath);

        final var root = openVfsRoot(subLog);
        if (root == null) {
            skipClasspathElement = true;
            return;
        }

        // Schedule the child classpath elements in the order the classloader would look in them, since the
        // classpath order determines which of two copies of the same class masks the other
        final var childScheduler = new ChildClasspathElementScheduler(workQueue, rawPath);

        // A package root within the jarfile is a classpath element in its own right, exactly as it is when the
        // jarfile has been exploded into a directory, so schedule it as one. It comes first, because a classloader
        // that serves an archive with a package root looks there before it looks in the archive's lib dirs. Only
        // look for a package root if this classpath element is not already one, since a package root does not
        // contain another.
        if (packageRootPrefix.isEmpty()) {
            for (final String packageRootPrefix : packageRootPrefixes) {
                final boolean packageRootExists;
                try {
                    packageRootExists = root.hasEntries(packageRootPrefix);
                } catch (final IOException e) {
                    if (subLog != null) {
                        subLog.log("Could not look for package root " + packageRootPrefix + " in " + rawPath + " : "
                                + e);
                    }
                    continue;
                }
                if (packageRootExists) {
                    if (subLog != null) {
                        subLog.log("Found package root: " + packageRootPrefix);
                    }
                    // Name the package root the way a nested classpath entry is named, so that the virtual
                    // filesystem opens the jarfile at the package root and reports the entries beneath it relative
                    // to it (the package root prefix of the child is found from that name when it is opened)
                    childScheduler.schedule(
                            zipFilePath + "!/" + packageRootPrefix.substring(0, packageRootPrefix.length() - 1),
                            /* childPackageRootPrefix = */ "");
                }
            }
        }

        // Then the classpath entries the jarfile declares, and last the jarfiles in its lib dirs
        final List<ChildEntry> childEntries;
        try {
            childEntries = ClasspathExpander.childEntries(root, libDirPrefixes, vfsSpec.isNestedJarsEnabled(),
                    subLog);
        } catch (final IOException e) {
            if (subLog != null) {
                subLog.log("Could not read the classpath elements declared by " + rawPath + " : " + e);
            }
            return;
        }
        for (final ChildEntry childEntry : childEntries) {
            if (subLog != null) {
                subLog.log(childEntry.origin().getLogMessage() + ": " + childEntry.location());
            }
            // A classpath element that this one declares has to pass the user's classpath element filters, the
            // same as one that was named directly
            if (!scanSpec.classpathSpec.acceptsClasspathElement(/* classpathElementURL = */ null,
                    childEntry.location())) {
                if (subLog != null) {
                    subLog.log(
                            "Classpath element did not match filter criterion, skipping: " + childEntry.location());
                }
                continue;
            }
            childScheduler.schedule(childEntry.location(), /* childPackageRootPrefix = */ "");
        }
    }

    /**
     * Get the resolved path of the outermost zipfile of this classpath element, i.e. everything in the raw path up
     * to the first nested jar separator.
     *
     * @return the resolved path of the outermost zipfile.
     */
    private String outermostZipFilePathResolved() {
        // The path is resolved before the separator is looked for, not after: a '!' only separates a jarfile from a
        // path within it if the path before it names a file, and that cannot be tested while the path still carries
        // a "file:" scheme prefix. A raw path in URL form was falling back to the rule used for remote URLs, where
        // the filesystem cannot be consulted and the first '!' is taken to be the separator, so a jar below a
        // directory whose name contains a '!' was truncated to the part before that directory
        // #903
        final var resolvedPath = FastPathResolver.resolve(FileUtils.currDirPath(), rawPath);
        final var plingIdx = PathSyntax.indexOfNestedJarSeparator(resolvedPath);
        return plingIdx < 0 ? resolvedPath : resolvedPath.substring(0, plingIdx);
    }

    /** The {@code "Implementation-Title"} that a jarfile of the JRE declares in its manifest. */
    private static final String JRE_IMPLEMENTATION_TITLE = "Java Runtime Environment";

    /** The {@code "Specification-Title"} that a jarfile of the JRE declares in its manifest. */
    private static final String JRE_SPECIFICATION_TITLE = "Java Platform API Specification";

    /**
     * Open this classpath element through the virtual filesystem, and record its normalized path and package root.
     *
     * @param log
     *            the log node, or null to skip logging
     * @return the jarfile, as a root of the virtual filesystem, or null if it could not be opened, or if it should
     *         not be scanned.
     */
    private @Nullable VfsRoot openVfsRoot(final @Nullable LogNode log) {
        final var outermostZipFilePathResolved = outermostZipFilePathResolved();
        if (!scanSpec.jarAcceptReject.isAcceptedAndNotRejected(outermostZipFilePathResolved)) {
            if (log != null) {
                log.log("Skipping jarfile that is rejected or not accepted: " + rawPath);
            }
            return null;
        }

        final VfsRoot root;
        try {
            // Open the innermost nested jarfile through the virtual filesystem, which strips any package root from
            // the names of the entries it reports
            root = vfs.open(rawPath, log);
            if (!(root instanceof ArchiveRoot)) {
                throw new IOException("Not a jarfile: " + rawPath);
            }
            this.vfsRoot = root;

            // Get the normalized path of the jarfile
            zipFilePath = FastPathResolver.resolve(FileUtils.currDirPath(), root.getPath());

            // Get package root of jarfile
            final var packageRoot = root.getPackageRoot();
            if (!packageRoot.isEmpty()) {
                packageRootPrefix = packageRoot + "/";
            }

            if (!scanSpec.enableSystemJars && isJREJar(root)) {
                // Skip a jarfile of the JRE (see isJREJar) unless system jars are enabled
                if (log != null) {
                    log.log("Ignoring JRE jar: " + rawPath);
                }
                return null;
            }
            // Only the standard locations of a Spring Boot jar's classes and lib jars are looked in, so a jar that
            // declares a different layout is skipped rather than scanned as if it held nothing
            checkSpringBootLayout(root, "Spring-Boot-Classes", "classes", "BOOT-INF/classes", "WEB-INF/classes");
            checkSpringBootLayout(root, "Spring-Boot-Lib", "lib jars", "BOOT-INF/lib", "WEB-INF/lib");

            // Read the explicitly declared automatic module name, if present. An ArchiveRoot opened at a package
            // root reports the manifest of the whole jarfile, which is the manifest that declares the name
            moduleNameFromManifestFile = root.getModuleName();
        } catch (final IOException | IllegalArgumentException e) {
            if (log != null) {
                log.log("Could not open jarfile " + rawPath + " : " + e);
            }
            return null;
        }

        if (!isAcceptedAndNotRejected()) {
            if (log != null) {
                log.log("Skipping jarfile that is rejected or not accepted: " + rawPath);
            }
            return null;
        }
        return root;
    }

    /**
     * Check whether a jarfile's manifest identifies it as part of the JRE.
     *
     * @param root
     *            the jarfile, as a root of the virtual filesystem
     * @return true if the jarfile is part of the JRE
     * @throws IOException
     *             if the manifest could not be read
     */
    private static boolean isJREJar(final VfsRoot root) throws IOException {
        return JRE_IMPLEMENTATION_TITLE.equalsIgnoreCase(root.getManifestEntry("Implementation-Title"))
                || JRE_SPECIFICATION_TITLE.equalsIgnoreCase(root.getManifestEntry("Specification-Title"));
    }

    /**
     * Verify that a Spring Boot manifest attribute, if present, names one of the standard locations.
     *
     * @param root
     *            the jarfile, as a root of the virtual filesystem
     * @param key
     *            the manifest attribute to check
     * @param description
     *            what is stored at the location, for the exception message
     * @param standardLocations
     *            the locations that are looked in
     * @throws IOException
     *             if the manifest could not be read, or the attribute names a location that is not one of the
     *             standard locations.
     */
    private static void checkSpringBootLayout(final VfsRoot root, final String key, final String description,
            final String... standardLocations) throws IOException {
        final var location = root.getManifestEntry(key);
        if (location == null) {
            return;
        }
        final var locationWithoutSlash = location.endsWith("/") ? location.substring(0, location.length() - 1)
                : location;
        for (final String standardLocation : standardLocations) {
            if (standardLocation.equals(locationWithoutSlash)) {
                return;
            }
        }
        throw new IOException("Spring Boot " + description + " are at \"" + location
                + "\" rather than the standard location \"" + String.join("/\" or \"", standardLocations)
                + "/\" -- please report this at https://github.com/classgraph/classgraph/issues");
    }

    /**
     * Check whether this jarfile and any outer jarfiles it is nested inside satisfy the jarfile accept/reject
     * criteria.
     *
     * @return true if this jarfile should be scanned
     */
    private boolean isAcceptedAndNotRejected() {
        // Find the separators the way the virtual filesystem found them when it opened the path. A "!/" before the
        // outermost jarfile belongs to a directory name, as in "/work/build!/lib.jar", so the outermost separator
        // is found by testing the filesystem. Within the outermost jarfile, the virtual filesystem joins each
        // nested jarfile's path to its parent's with "!/", so there every "!/" is a separator (#903).
        final var outermostSepIdx = PathSyntax.indexOfNestedJarSeparator(zipFilePath);
        if (!scanSpec.jarAcceptReject.isAcceptedAndNotRejected(
                outermostSepIdx < 0 ? zipFilePath : zipFilePath.substring(0, outermostSepIdx))) {
            return false;
        }
        if (outermostSepIdx >= 0) {
            for (final String nestedJarPath : zipFilePath.substring(outermostSepIdx + 1).split("!/")) {
                if (!nestedJarPath.isEmpty() && !scanSpec.jarAcceptReject.isAcceptedAndNotRejected(nestedJarPath)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Get the {@link Resource} for a given relative path.
     *
     * @param relativePath
     *            The relative path of the {@link Resource} to return.
     * @return A new {@link Resource} for the given relative path, or null if relativePath does not exist in this
     *         classpath element, or is a path that the scan skips.
     */
    @Override
    @Nullable
    Resource getResource(final String relativePath) {
        final var root = vfsRoot;
        if (root == null || skipClasspathElement || isSkippedPath(relativePath)) {
            return null;
        }
        try {
            final var entry = root.getEntry(relativePath);
            return entry == null ? null : new Resource(this, entry, relativePath);
        } catch (final IOException e) {
            return null;
        }
    }

    /**
     * Applies the scan spec to the entries of the jarfile as the virtual filesystem enumerates them, and records
     * the accepted ones.
     */
    private final class ZipScanVisitor implements VfsVisitor {
        /** The log node, or null to skip logging. */
        private final @Nullable LogNode subLog;

        /** The nested classpath roots that have already been logged, so that each is logged only once. */
        private final Set<String> loggedNestedClasspathRootPrefixes = new HashSet<>();

        /** The match status of the directory whose entries are currently being visited. */
        private ScanSpecPathMatch parentMatchStatus = ScanSpecPathMatch.NOT_WITHIN_ACCEPTED_PATH;

        /**
         * Constructor.
         *
         * @param subLog
         *            the log node, or null to skip logging
         */
        ZipScanVisitor(final @Nullable LogNode subLog) {
            this.subLog = subLog;
        }

        @Override
        public boolean enterDirectory(final String dirName) {
            parentMatchStatus = scanSpec.dirAcceptMatchStatus(dirName.isEmpty() ? "/" : dirName);
            return true;
        }

        @Override
        public boolean visitEntry(final VfsEntry entry) {
            final var entryName = entry.getPathFromRoot();

            if (isIgnoredVersionedPath(entryName)) {
                if (subLog != null) {
                    subLog.log("Found unexpected versioned entry in jar (the jar's manifest file may be missing "
                            + "the \"Multi-Release\" key) -- skipping: " + entryName);
                }
                return true;
            }

            if (isWithinNestedClasspathRoot(entryName, loggedNestedClasspathRootPrefixes, subLog)) {
                return true;
            }

            // Accept/reject classpath elements based on file resource paths
            if (!checkResourcePathAcceptReject(entryName, subLog)) {
                // The whole classpath element is rejected, so stop scanning the rest of it
                return false;
            }

            if (parentMatchStatus == ScanSpecPathMatch.HAS_REJECTED_PATH_PREFIX && subLog != null) {
                // The parent dir or one of its ancestral dirs is rejected, so the entry is not accepted. It can
                // still be found by getResource(String), which looks the entry up in the jarfile, and which is how
                // the class graph is extended upwards through classes that the scan did not accept
                subLog.log("Not accepting rejected path: " + entryName);
            }

            addZipEntryResource(entry, entryName, parentMatchStatus, subLog);
            return true;
        }
    }

    /**
     * Scan for path matches within jarfile, and record ZipEntry objects of matching files.
     *
     * @param log
     *            the log node, or null to skip logging
     */
    @Override
    void scanPaths(final @Nullable LogNode log) {
        if (this.vfsRoot == null) {
            skipClasspathElement = true;
        }
        if (skipClasspathElement) {
            return;
        }
        if (scanned.getAndSet(true)) {
            // Should not happen
            throw new IllegalStateException("Already scanned classpath element " + getZipFilePath());
        }

        final var root = Objects.requireNonNull(this.vfsRoot);

        final var subLog = log == null ? null
                : log(classpathElementIdx, "Scanning jarfile classpath element " + getZipFilePath(), log);

        try {
            root.walk(new ZipScanVisitor(subLog), subLog);
        } catch (final IOException e) {
            if (subLog != null) {
                subLog.log("Could not read jarfile " + getZipFilePath() + " : " + e);
            }
        }

        // Save the last modified time for the zipfile
        final var zipfile = getFile();
        if (zipfile != null) {
            fileToLastModified.put(zipfile, zipfile.lastModified());
        }

        finishScanPaths(subLog);
    }

    /**
     * Check whether a zip entry is within a nested classpath root, i.e. within a classpath element that is nested
     * inside this classpath element, and that will therefore be scanned separately.
     *
     * @param entryName
     *            the name of the zip entry
     * @param loggedNestedClasspathRootPrefixes
     *            the nested classpath roots that have already been logged, so that each is logged only once
     * @param log
     *            the log node, or null to skip logging
     * @return true if the entry is within a nested classpath root, and should therefore not be scanned
     */
    private boolean isWithinNestedClasspathRoot(final String entryName,
            final Set<String> loggedNestedClasspathRootPrefixes, final @Nullable LogNode log) {
        final var nestedClasspathRoot = nestedClasspathRootOf(entryName);
        if (nestedClasspathRoot == null) {
            return false;
        }
        if (log != null && loggedNestedClasspathRootPrefixes.add(nestedClasspathRoot)) {
            log.log("Reached nested classpath root, stopping recursion to avoid duplicate scanning: "
                    + nestedClasspathRoot);
        }
        return true;
    }

    /**
     * If a zip entry is accepted, or is the module descriptor, add it as a {@link Resource} and schedule it for
     * scanning. No two entries of a jarfile have the same path, so each path is added at most once.
     *
     * @param entry
     *            the zip entry, as an entry in the virtual filesystem
     * @param relativePath
     *            the path of the entry relative to the package root
     * @param parentMatchStatus
     *            the match status of the parent dir of the entry
     * @param log
     *            the log node, or null to skip logging
     */
    private void addZipEntryResource(final VfsEntry entry, final String relativePath,
            final ScanSpecPathMatch parentMatchStatus, final @Nullable LogNode log) {
        if (isAcceptedResourcePath(relativePath, parentMatchStatus)) {
            // Resource is accepted
            addAcceptedResource(new Resource(this, entry, relativePath), parentMatchStatus,
                    /* isClassfileOnly = */ false, log);
        } else if (scanSpec.enableClassInfo && "module-info.class".equals(relativePath)) {
            // Add module descriptor as an accepted classfile resource, so that it is scanned, but don't add it to
            // the list of resources in the ScanResult, since it is not in an accepted package (#352)
            addAcceptedResource(new Resource(this, entry, relativePath), parentMatchStatus,
                    /* isClassfileOnly = */ true, log);
        }
    }

    /**
     * Get the module name declared by the jarfile, or the automatic module name derived from its filename if none
     * is declared.
     *
     * @return the module name, or null if the jar declares none and no name can be derived from its filename.
     */
    @Override
    public @Nullable String getModuleName() {
        final var declaredModuleName = getDeclaredModuleName();
        if (declaredModuleName != null) {
            return declaredModuleName;
        }
        if (derivedAutomaticModuleName == null) {
            derivedAutomaticModuleName = AutomaticModuleName.derive(zipFilePath);
        }
        return derivedAutomaticModuleName.isEmpty() ? null : derivedAutomaticModuleName;
    }

    /**
     * Get the zipfile path.
     *
     * @return the path of the zipfile, including any package root.
     */
    String getZipFilePath() {
        return packageRootPrefix.isEmpty() ? zipFilePath
                : zipFilePath + "!/" + packageRootPrefix.substring(0, packageRootPrefix.length() - 1);
    }

    @Override
    URI getURI() {
        return URLPaths.toURI(getZipFilePath());
    }

    @Override
    List<URI> getAllURIs() {
        return List.of(getURI());
    }

    /**
     * Get the {@link File} for the outermost zipfile of this classpath element.
     *
     * @return The {@link File} for the outermost zipfile of this classpath element, or null if that zipfile was
     *         downloaded from a URL directly to RAM, or if the classpath element was backed by a custom filesystem
     *         that supports the {@link Path} API but not the {@link File} API.
     */
    @Override
    @Nullable
    File getFile() {
        if (vfsRoot != null) {
            return vfsRoot.getFile();
        } else {
            // The jarfile was never opened, because jar scanning is disabled, or because it could not be opened
            return new File(outermostZipFilePathResolved());
        }
    }

    /**
     * Return the classpath element path.
     *
     * @return the path of the zipfile
     */
    @Override
    public String toString() {
        return getZipFilePath();
    }

    @Override
    public boolean equals(final @Nullable Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof final ClasspathElementZip other)) {
            return false;
        }
        return this.getZipFilePath().equals(other.getZipFilePath());
    }

    @Override
    public int hashCode() {
        return getZipFilePath().hashCode();
    }
}
