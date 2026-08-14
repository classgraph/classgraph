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
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.classgraph.Scanner.ClasspathEntryWorkUnit;
import io.github.classgraph.base.internal.concurrency.WorkQueue;
import io.github.classgraph.base.internal.utils.FastPathResolver;
import io.github.classgraph.base.internal.utils.FileUtils;
import io.github.classgraph.base.internal.utils.JarUtils;
import io.github.classgraph.base.internal.utils.LogNode;
import io.github.classgraph.base.internal.utils.URLPathEncoder;
import io.github.classgraph.classpath.internal.ClasspathExpander.ChildEntry;
import io.github.classgraph.classpath.internal.ClasspathExpander;
import io.github.classgraph.internal.scanspec.ScanSpec.ScanSpecPathMatch;
import io.github.classgraph.internal.scanspec.ScanSpec;
import io.github.classgraph.vfs.internal.slice.reader.ClassfileReader;
import io.github.classgraph.vfs.internal.zip.LogicalZipFile;
import io.github.classgraph.vfs.Vfs;
import io.github.classgraph.vfs.VfsEntry;
import io.github.classgraph.vfs.VfsRoot;
import io.github.classgraph.vfs.VfsVisitor;
import org.jspecify.annotations.Nullable;

/** A zip/jarfile classpath element. */
class ClasspathElementZip extends ClasspathElement {
    /** No automatic package root prefix is stripped from the entry names of this classpath element. */
    private static final String[] NO_PACKAGE_ROOT_PREFIXES = {};

    /**
     * The {@link String} representation of the path string, {@link URL}, {@link URI}, or {@link Path} for this
     * zipfile.
     */
    private final String rawPath;
    /**
     * The normalized path of the jarfile, "!/"-separated if nested, excluding any package root.
     */
    private String zipFilePath;
    /**
     * A map from relative path to {@link Resource} for non-rejected zip entries.
     */
    private final ConcurrentHashMap<String, Resource> relativePathToResource = new ConcurrentHashMap<>();
    /**
     * A list of all automatic package root prefixes found as prefixes of paths within this zipfile.
     */
    private final Set<String> strippedAutomaticPackageRootPrefixes = new HashSet<>();
    /** The virtual filesystem that the jarfile is enumerated and read through. */
    private final Vfs vfs;
    /**
     * The jarfile, as a root of the virtual filesystem, or null until {@link #open} has been called (or if the
     * classpath element could not be opened).
     */
    @Nullable
    VfsRoot vfsRoot;
    /**
     * The name of the module from the {@code Automatic-Module-Name} manifest attribute, if one is present in the
     * root of the classpath element.
     */
    @Nullable
    String moduleNameFromManifestFile;
    /** The automatic module name, derived from the jarfile filename. */
    private @Nullable String derivedAutomaticModuleName;

    /**
     * A jarfile classpath element.
     *
     * @param workUnit
     *            the work unit
     * @param vfs
     *            the virtual filesystem to enumerate and read the jarfile through
     * @param scanSpec
     *            the scan spec
     */
    ClasspathElementZip(final ClasspathEntryWorkUnit workUnit, final Vfs vfs, final ScanSpec scanSpec) {
        super(workUnit, scanSpec);
        final var rawPathObj = Objects.requireNonNull(workUnit.classpathEntryObj);

        // Convert the raw path object (Path, URL, or URI) to a string. Any required URL/URI parsing are done in
        // NestedJarHandler.
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

    /**
     * Schedules the child classpath elements found within this classpath element -- nested lib jars, and the
     * entries of the manifest's {@code Class-Path} and {@code Bundle-ClassPath} attributes -- for scanning.
     */
    private final class ChildClasspathElementScheduler {
        /** The work queue to add child classpath elements to. */
        private final WorkQueue<ClasspathEntryWorkUnit> workQueue;
        /**
         * The child classpath elements that have already been scheduled, so that no child classpath element is
         * scheduled twice, and so that this classpath element is not scheduled as a child of itself.
         */
        private final Set<String> alreadyScheduled = new HashSet<>();
        /** The order of the next child classpath element within this classpath element. */
        private int childClasspathEntryIdx;

        ChildClasspathElementScheduler(final WorkQueue<ClasspathEntryWorkUnit> workQueue) {
            this.workQueue = workQueue;
            alreadyScheduled.add(rawPath);
        }

        /**
         * Schedule a child classpath element for scanning, unless it has already been scheduled.
         *
         * @param childClasspathEltPath
         *            the path of the child classpath element
         * @throws InterruptedException
         *             if the thread was interrupted
         */
        void schedule(final String childClasspathEltPath) throws InterruptedException {
            if (alreadyScheduled.add(childClasspathEltPath)) {
                workQueue.addWorkUnit(new ClasspathEntryWorkUnit(childClasspathEltPath, getClassLoaderString(),
                        /* parentClasspathElement = */ ClasspathElementZip.this,
                        /* orderWithinParentClasspathElement = */ childClasspathEntryIdx++,
                        /* packageRootPrefix = */ "", packageRootPrefixes));
            }
        }
    }

    @Override
    void open(final WorkQueue<ClasspathEntryWorkUnit> workQueue, final @Nullable LogNode log)
            throws InterruptedException {
        if (!scanSpec.scanJars) {
            if (log != null) {
                log(classpathElementIdx, "Skipping classpath element, since jar scanning is disabled: " + rawPath,
                        log);
            }
            skipClasspathElement = true;
            return;
        }
        final var subLog = log == null ? null : log(classpathElementIdx, "Opening jar: " + rawPath, log);

        final var logicalZipFile = openLogicalZipFile(subLog);
        if (logicalZipFile == null) {
            skipClasspathElement = true;
            return;
        }

        // Schedule the child classpath entries in the order they were found, since the classpath order determines
        // which of two copies of the same class masks the other
        final var childScheduler = new ChildClasspathElementScheduler(workQueue);
        for (final ChildEntry childEntry : ClasspathExpander.childEntries(logicalZipFile, zipFilePath,
                vfsScanSpec.enableNestedJars)) {
            if (subLog != null) {
                subLog.log(childEntry.origin().getLogMessage() + ": " + childEntry.path());
            }
            childScheduler.schedule(childEntry.path());
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
        final var plingIdx = JarUtils.indexOfNestedJarSeparator(resolvedPath);
        return plingIdx < 0 ? resolvedPath : resolvedPath.substring(0, plingIdx);
    }

    /**
     * Open the {@link LogicalZipFile} for this classpath element, and record its normalized path and package root.
     *
     * @param log
     *            the log node, or null to skip logging
     * @return the {@link LogicalZipFile}, or null if the zipfile could not be opened, or if it should not be
     *         scanned.
     */
    private @Nullable LogicalZipFile openLogicalZipFile(final @Nullable LogNode log) {
        final var outermostZipFilePathResolved = outermostZipFilePathResolved();
        if (!scanSpec.jarAcceptReject.isAcceptedAndNotRejected(outermostZipFilePathResolved)) {
            if (log != null) {
                log.log("Skipping jarfile that is rejected or not accepted: " + rawPath);
            }
            return null;
        }

        final LogicalZipFile logicalZipFile;
        try {
            // Open the innermost nested jarfile through the virtual filesystem, which strips any package root from
            // the names of the entries it reports
            final var root = vfs.open(rawPath, log);
            final var openedZipFile = root.getLogicalZipFile();
            if (openedZipFile == null) {
                throw new IOException("Not a jarfile: " + rawPath);
            }
            this.vfsRoot = root;
            logicalZipFile = openedZipFile;

            // Get the normalized path of the jarfile
            zipFilePath = FastPathResolver.resolve(FileUtils.currDirPath(), root.getPath());

            // Get package root of jarfile
            final var packageRoot = root.getPackageRoot();
            if (!packageRoot.isEmpty()) {
                packageRootPrefix = packageRoot + "/";
            }
        } catch (final IOException | IllegalArgumentException e) {
            if (log != null) {
                log.log("Could not open jarfile " + rawPath + " : " + e);
            }
            return null;
        }

        if (!scanSpec.classpathSpec.enableSystemJarsAndModules && logicalZipFile.isJREJar) {
            // Found a rejected JRE jar that was not caught by filtering for rt.jar in ClassLoaderProbe (the isJREJar
            // value was set by detecting JRE headers in the jar's manifest file)
            if (log != null) {
                log.log("Ignoring JRE jar: " + rawPath);
            }
            return null;
        }

        if (!logicalZipFile.isAcceptedAndNotRejected(scanSpec.jarAcceptReject)) {
            if (log != null) {
                log.log("Skipping jarfile that is rejected or not accepted: " + rawPath);
            }
            return null;
        }
        return logicalZipFile;
    }

    /**
     * Create a new {@link Resource} object for a resource or classfile discovered while scanning paths.
     *
     * @param entry
     *            the entry in the virtual filesystem
     * @param pathRelativeToPackageRoot
     *            the path relative to package root
     * @return the resource
     */
    private Resource newResource(final VfsEntry entry, final String pathRelativeToPackageRoot) {
        return new ZipResource(entry, pathRelativeToPackageRoot);
    }

    /**
     * A {@link Resource} for an entry in a zipfile classpath element.
     */
    private final class ZipResource extends Resource {
        /**
         * Constructor.
         *
         * @param entry
         *            the zip entry of the resource, as an entry in the virtual filesystem.
         * @param pathRelativeToPackageRoot
         *            the path of the resource, relative to the package root, i.e. with the package root prefix
         *            and/or any Spring Boot prefix ({@code "BOOT-INF/classes/"} or {@code "WEB-INF/classes/"})
         *            removed.
         */
        ZipResource(final VfsEntry entry, final String pathRelativeToPackageRoot) {
            super(ClasspathElementZip.this, entry, pathRelativeToPackageRoot);
        }

        @Override
        public String getPathRelativeToClasspathElement() {
            // The name of the entry in the zipfile, which for an entry of a multi-release jar is the versioned name
            final var entryName = getVfsEntry().getStoredName();
            return entryName.startsWith(packageRootPrefix) ? entryName.substring(packageRootPrefix.length())
                    : entryName;
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
        return relativePathToResource.get(relativePath);
    }

    /**
     * Filter out any candidate package root prefix that is really a package with the same name as the prefix, e.g.
     * a package named {@code classes} in a jar that has no {@code classes/} package root.
     *
     * @param root
     *            the jarfile, as a root of the virtual filesystem
     * @param log
     *            the log node, or null to skip logging
     * @return the package root prefixes that were not disproved
     */
    // #929
    private String[] getVerifiedPackageRootPrefixes(final VfsRoot root, final @Nullable LogNode log) {
        final List<VfsEntry> entries;
        try {
            entries = root.getEntries();
        } catch (final IOException e) {
            // The walk that follows lists the same entries, and logs the reason if they cannot be listed
            return NO_PACKAGE_ROOT_PREFIXES;
        }
        // Find the first classfile beneath each candidate package root prefix
        final var firstClassfileEntry = new VfsEntry[packageRootPrefixes.length];
        for (final VfsEntry entry : entries) {
            final var entryName = entry.getName();
            if (!entryName.endsWith(".class")) {
                continue;
            }
            for (var i = 0; i < packageRootPrefixes.length; i++) {
                final var prefix = packageRootPrefixes[i];
                if (firstClassfileEntry[i] == null && entryName.startsWith(prefix)
                // The path of a classfile below META-INF (e.g. in a multi-release jar) does not necessarily
                // correspond to the name of the class it declares
                        && !entryName.startsWith("META-INF/", prefix.length())) {
                    firstClassfileEntry[i] = entry;
                }
            }
        }
        // Check the class declared by each of those classfiles against its path
        final List<String> verifiedPackageRootPrefixes = new ArrayList<>(packageRootPrefixes.length);
        for (var i = 0; i < packageRootPrefixes.length; i++) {
            final var prefix = packageRootPrefixes[i];
            final var entry = firstClassfileEntry[i];
            String disprovingClassName = null;
            if (entry != null) {
                try (var classfileReader = new ClassfileReader(entry)) {
                    disprovingClassName = getClassNameDisprovingPackageRoot(classfileReader,
                            entry.getName().substring(prefix.length()));
                } catch (final IOException e) {
                    // If the classfile cannot be read, give the candidate package root the benefit of the doubt
                }
            }
            if (disprovingClassName == null) {
                verifiedPackageRootPrefixes.add(prefix);
            } else if (log != null) {
                log.log("\"" + prefix + "\" is a package, not a package root, since a classfile beneath it "
                        + "declares the class " + disprovingClassName);
            }
        }
        return verifiedPackageRootPrefixes.toArray(String[]::new);
    }

    /**
     * Applies the scan spec to the entries of the jarfile as the virtual filesystem enumerates them, and records
     * the accepted ones.
     */
    private final class ZipScanVisitor implements VfsVisitor {
        /** True if the jarfile declares a module name. */
        private final boolean isModularJar;

        /** The automatic package root prefixes to strip from the names of the entries. */
        private final String[] automaticPackageRootPrefixes;

        /** The log node, or null to skip logging. */
        private final @Nullable LogNode subLog;

        /** The nested classpath roots that have already been logged, so that each is logged only once. */
        private final Set<String> loggedNestedClasspathRootPrefixes = new HashSet<>();

        /** The match status of the directory whose entries are currently being visited. */
        private ScanSpecPathMatch parentMatchStatus = ScanSpecPathMatch.NOT_WITHIN_ACCEPTED_PATH;

        /**
         * Constructor.
         *
         * @param isModularJar
         *            true if the jarfile declares a module name
         * @param automaticPackageRootPrefixes
         *            the automatic package root prefixes to strip from the names of the entries
         * @param subLog
         *            the log node, or null to skip logging
         */
        ZipScanVisitor(final boolean isModularJar, final String[] automaticPackageRootPrefixes,
                final @Nullable LogNode subLog) {
            this.isModularJar = isModularJar;
            this.automaticPackageRootPrefixes = automaticPackageRootPrefixes;
            this.subLog = subLog;
        }

        @Override
        public boolean enterDirectory(final String dirName) {
            // The entries of a directory are named before any automatic package root prefix is stripped, so the
            // prefix has to be stripped from the directory name too, for the accept/reject criteria to judge the
            // directory by the same path that they judge the entries in it by
            final var relativeDirName = stripAutomaticPackageRootPrefix(dirName, automaticPackageRootPrefixes,
                    /* recordStrippedPrefix = */ false);
            parentMatchStatus = scanSpec.dirAcceptMatchStatus(relativeDirName.isEmpty() ? "/" : relativeDirName);
            return true;
        }

        @Override
        public boolean visitEntry(final VfsEntry entry) {
            final var entryName = entry.getName();

            if (isIgnoredVersionedPath(entryName)) {
                if (subLog != null) {
                    subLog.log("Found unexpected versioned entry in jar (the jar's manifest file may be missing "
                            + "the \"Multi-Release\" key) -- skipping: " + entryName);
                }
                return true;
            }

            if (isIgnoredDefaultPackageClassfile(isModularJar, entryName)) {
                return true;
            }

            if (isWithinNestedClasspathRoot(entryName, loggedNestedClasspathRootPrefixes, subLog)) {
                return true;
            }

            final var relativePath = stripAutomaticPackageRootPrefix(entryName, automaticPackageRootPrefixes,
                    /* recordStrippedPrefix = */ true);

            // Accept/reject classpath elements based on file resource paths
            if (!checkResourcePathAcceptReject(relativePath, subLog)) {
                // The whole classpath element is rejected, so stop scanning the rest of it
                return false;
            }

            if (parentMatchStatus == ScanSpecPathMatch.HAS_REJECTED_PATH_PREFIX) {
                // The parent dir or one of its ancestral dirs is rejected
                if (subLog != null) {
                    subLog.log("Skipping rejected path: " + relativePath);
                }
                return true;
            }

            addZipEntryResource(entry, relativePath, parentMatchStatus, subLog);
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

        // A jar is modular only if it declares a module name -- an automatic module name derived from the jar name
        // does not make the jar modular
        final var isModularJar = getDeclaredModuleName() != null;

        // An explicit package root has already been stripped from the names of the entries by the virtual
        // filesystem, and rules out stripping an automatic package root prefix as well. "classes/" and
        // "test-classes/" are legal package names, so only strip an automatic package root prefix from the relative
        // path of an entry if the prefix is not simply a package with the same name (#929)
        final var automaticPackageRootPrefixes = packageRootPrefix.isEmpty() && packageRootPrefixes.length > 0
                ? getVerifiedPackageRootPrefixes(root, subLog)
                : NO_PACKAGE_ROOT_PREFIXES;

        try {
            root.walk(new ZipScanVisitor(isModularJar, automaticPackageRootPrefixes, subLog), subLog);
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
        if (nestedClasspathRootPrefixes == null) {
            return false;
        }
        // This is O(mn), which is inefficient, but the number of nested classpath roots should be small
        for (final String nestedClasspathRoot : nestedClasspathRootPrefixes) {
            if (entryName.startsWith(nestedClasspathRoot)) {
                if (log != null && loggedNestedClasspathRootPrefixes.add(nestedClasspathRoot)) {
                    log.log("Reached nested classpath root, stopping recursion to avoid duplicate scanning: "
                            + nestedClasspathRoot);
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Strip any automatic package root prefix, e.g. {@code "BOOT-INF/classes/"}, from the name of a zip entry or of
     * a directory within the jarfile, to give the path relative to the package root.
     *
     * @param name
     *            the name of the zip entry or directory, relative to any explicit package root
     * @param automaticPackageRootPrefixes
     *            the automatic package root prefixes to strip
     * @param recordStrippedPrefix
     *            if true, record the prefix that was stripped, for use by {@link #getAllURIs()}
     * @return the path relative to the package root
     */
    private String stripAutomaticPackageRootPrefix(final String name, final String[] automaticPackageRootPrefixes,
            final boolean recordStrippedPrefix) {
        for (final String packageRoot : automaticPackageRootPrefixes) {
            if (name.startsWith(packageRoot)) {
                if (recordStrippedPrefix) {
                    // Strip final slash from package root, and store the package root for use by getAllURIs()
                    strippedAutomaticPackageRootPrefixes
                            .add(packageRoot.endsWith("/") ? packageRoot.substring(0, packageRoot.length() - 1)
                                    : packageRoot);
                }
                // Only one package root prefix can be stripped from a given path
                return name.substring(packageRoot.length());
            }
        }
        return name;
    }

    /**
     * Add a zip entry as a {@link Resource}, and, if the resource is accepted, schedule it for scanning.
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
        final var resource = newResource(entry, relativePath);
        if (relativePathToResource.putIfAbsent(relativePath, resource) == null) {
            if (isAcceptedResourcePath(relativePath, parentMatchStatus)) {
                // Resource is accepted
                addAcceptedResource(resource, parentMatchStatus, /* isClassfileOnly = */ false, log);
            } else if (scanSpec.enableClassInfo && "module-info.class".equals(relativePath)) {
                // Add module descriptor as an accepted classfile resource, so that it is scanned, but don't add it
                // to the list of resources in the ScanResult, since it is not in an accepted package (#352)
                addAcceptedResource(resource, parentMatchStatus, /* isClassfileOnly = */ true, log);
            }
        }
    }

    /**
     * Get the module name declared by the jarfile, either by its {@code module-info.class} module descriptor, or by
     * the {@code Automatic-Module-Name} attribute of its manifest file.
     *
     * @return the declared module name, or null if the jarfile does not declare one.
     */
    private @Nullable String getDeclaredModuleName() {
        if (moduleNameFromModuleDescriptor != null && !moduleNameFromModuleDescriptor.isEmpty()) {
            return moduleNameFromModuleDescriptor;
        }
        return moduleNameFromManifestFile == null || moduleNameFromManifestFile.isEmpty() ? null
                : moduleNameFromManifestFile;
    }

    /**
     * Get the module name declared by the jarfile, or, if it declares none, an automatic module name derived from
     * the jar name.
     *
     * @return the module name
     */
    @Override
    public @Nullable String getModuleName() {
        final var declaredModuleName = getDeclaredModuleName();
        if (declaredModuleName != null) {
            return declaredModuleName;
        }
        if (derivedAutomaticModuleName == null) {
            derivedAutomaticModuleName = JarUtils.derivedAutomaticModuleName(zipFilePath);
        }
        return derivedAutomaticModuleName == null || derivedAutomaticModuleName.isEmpty() ? null
                : derivedAutomaticModuleName;
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
        try {
            return new URI(URLPathEncoder.normalizeURLPath(getZipFilePath()));
        } catch (final URISyntaxException e) {
            throw new IllegalStateException("Could not form URI: " + e);
        }
    }

    /**
     * Return URI for classpath element, plus URIs for any stripped nested automatic package root prefixes, e.g.
     * "!/BOOT-INF/classes".
     */
    @Override
    List<URI> getAllURIs() {
        if (strippedAutomaticPackageRootPrefixes.isEmpty()) {
            return List.of(getURI());
        } else {
            final var uri = getURI();
            final List<URI> uris = new ArrayList<>();
            uris.add(uri);
            final var uriStr = uri.toString();
            for (final String prefix : strippedAutomaticPackageRootPrefixes) {
                try {
                    uris.add(new URI(uriStr + "!/" + prefix));
                } catch (final URISyntaxException e) {
                    // Ignore
                }
            }
            return uris;
        }
    }

    /**
     * Get the {@link File} for the outermost zipfile of this classpath element.
     *
     * @return The {@link File} for the outermost zipfile of this classpath element, or null if this file was
     *         downloaded from a URL directly to RAM, or if the classpath element was backed by a custom filesystem
     *         that supports the {@link Path} API put not the {@link File} API.
     */
    @Override
    @Nullable
    File getFile() {
        if (vfsRoot != null) {
            return vfsRoot.getFile();
        } else {
            // Not performing a full scan (only getting classpath elements), so the jarfile was never opened
            return new File(outermostZipFilePathResolved());
        }
    }

    /**
     * Return the classpath element path.
     *
     * @return the string
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
