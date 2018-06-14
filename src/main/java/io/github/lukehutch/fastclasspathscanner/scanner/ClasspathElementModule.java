/*
 * This file is part of FastClasspathScanner.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/lukehutch/fast-classpath-scanner
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2016 Luke Hutchison
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
package io.github.lukehutch.fastclasspathscanner.scanner;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import io.github.lukehutch.fastclasspathscanner.scanner.ModuleRef.ModuleReaderProxy;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanSpec.ScanSpecPathMatch;
import io.github.lukehutch.fastclasspathscanner.scanner.matchers.FileMatchProcessorWrapper;
import io.github.lukehutch.fastclasspathscanner.utils.FileUtils;
import io.github.lukehutch.fastclasspathscanner.utils.FileUtils.ByteBufferBackedInputStream;
import io.github.lukehutch.fastclasspathscanner.utils.InterruptionChecker;
import io.github.lukehutch.fastclasspathscanner.utils.LogNode;
import io.github.lukehutch.fastclasspathscanner.utils.MultiMapKeyToList;
import io.github.lukehutch.fastclasspathscanner.utils.NestedJarHandler;
import io.github.lukehutch.fastclasspathscanner.utils.Recycler;

/** A module classpath element. */
class ClasspathElementModule extends ClasspathElement {
    private final ModuleRef moduleRef;

    private Recycler<ModuleReaderProxy, IOException> moduleReaderProxyRecycler;

    /** A zip/jarfile classpath element. */
    ClasspathElementModule(final RelativePath classpathEltPath, final ScanSpec scanSpec, final boolean scanFiles,
            final NestedJarHandler nestedJarHandler, final InterruptionChecker interruptionChecker,
            final LogNode log) {
        super(classpathEltPath, scanSpec, scanFiles, interruptionChecker);
        moduleRef = classpathEltPath.getModuleRef();
        if (moduleRef == null) {
            // Should not happen
            throw new IllegalArgumentException();
        }
        try {
            moduleReaderProxyRecycler = nestedJarHandler.getModuleReaderProxyRecycler(moduleRef, log);
        } catch (final Exception e) {
            if (log != null) {
                log.log("Exception while creating zipfile recycler for " + moduleRef.getModuleName() + " : " + e);
            }
            ioExceptionOnOpen = true;
            return;
        }
        if (scanFiles) {
            fileMatches = new MultiMapKeyToList<>();
            classfileMatches = new ArrayList<>();
            fileToLastModified = new HashMap<>();
        }
    }

    /** Scan for package matches within module */
    @Override
    public void scanPaths(final LogNode log) {
        final String moduleLocationStr = moduleRef.getModuleLocationStr();
        final LogNode logNode = log == null ? null
                : log.log(moduleLocationStr, "Scanning module classpath entry " + classpathEltPath);
        ModuleReaderProxy moduleReaderProxy = null;
        try {
            try {
                moduleReaderProxy = moduleReaderProxyRecycler.acquire();
            } catch (final IOException e) {
                if (logNode != null) {
                    logNode.log("Exception opening module " + classpathEltPath, e);
                }
                ioExceptionOnOpen = true;
                return;
            }
            scanModule(moduleReaderProxy, logNode);
        } finally {
            moduleReaderProxyRecycler.release(moduleReaderProxy);
        }
        if (logNode != null) {
            logNode.addElapsedTime();
        }
    }

    private ClasspathResource newClasspathResource(final String moduleResourcePath) {
        return new ClasspathResource(/* classpathEltFile = */ null, moduleRef, moduleResourcePath,
                moduleResourcePath) {
            private ModuleReaderProxy moduleReaderProxy = null;
            private InputStream inputStream = null;
            private ByteBuffer byteBuffer = null;

            @Override
            public InputStream open() throws IOException {
                if (ioExceptionOnOpen) {
                    // Can't open a file inside a module if the module couldn't be opened (should never be
                    // triggered)
                    throw new IOException("Module could not be opened");
                }
                try {
                    if (moduleReaderProxy != null || inputStream != null) {
                        // Should not happen, since this will only be called from single-threaded code when
                        // MatchProcessors are running
                        throw new RuntimeException("Tried to open classpath resource twice");
                    }
                    moduleReaderProxy = moduleReaderProxyRecycler.acquire();

                    // TODO: modify file match processor API to support ByteBuffers directly, rather than wrapping
                    // them in an InputStream. (Maybe wrap InputStream in a ByteBuffer instead).
                    byteBuffer = moduleReaderProxy.read(moduleResourcePath);
                    inputStream = new ByteBufferBackedInputStream(byteBuffer);

                    inputStreamLength = byteBuffer.remaining();
                    return inputStream;
                } catch (final Exception e) {
                    close();
                    throw new IOException("Could not open " + this, e);
                }
            }

            @Override
            public void close() {
                if (inputStream != null) {
                    try {
                        inputStream.close();
                    } catch (final Exception e) {
                        // Ignore
                    }
                    inputStream = null;
                }
                if (byteBuffer != null) {
                    try {
                        moduleReaderProxy.release(byteBuffer);
                    } catch (final Exception e) {
                        // Ignore
                    }
                    byteBuffer = null;
                }
                if (moduleReaderProxy != null) {
                    moduleReaderProxyRecycler.release(moduleReaderProxy);
                    moduleReaderProxy = null;
                }
            }
        };
    }

    /**
     * Scan a module for packages matching the scan spec.
     * 
     * @param moduleRef
     */
    private void scanModule(final ModuleReaderProxy moduleReaderProxy, final LogNode log) {
        // Always scan a module if the root package needs to be scanned
        boolean hasWhitelistedPackage = scanSpec.whitelistedPathsNonRecursive.contains("");
        if (!hasWhitelistedPackage) {
            // Check if module contains any whitelisted non-root packages
            final List<String> packages = moduleRef.getModulePackages();
            for (final String pkg : packages) {
                final String pkgPath = pkg.replace('.', '/') + "/";
                final ScanSpecPathMatch matchStatus = scanSpec.dirWhitelistMatchStatus(pkgPath);
                if (matchStatus == ScanSpecPathMatch.HAS_WHITELISTED_PATH_PREFIX
                        || matchStatus == ScanSpecPathMatch.AT_WHITELISTED_PATH
                        || matchStatus == ScanSpecPathMatch.AT_WHITELISTED_CLASS_PACKAGE) {
                    hasWhitelistedPackage = true;
                    break;
                }
            }
        }
        if (hasWhitelistedPackage) {
            // Look for whitelisted files in the module.
            List<String> resourceRelativePaths;
            try {
                resourceRelativePaths = moduleReaderProxy.list();
            } catch (final Exception e) {
                if (log != null) {
                    log.log("Could not get resource list for module " + moduleRef.getModuleName(), e);
                }
                return;
            }
            String prevParentRelativePath = null;
            ScanSpecPathMatch prevParentMatchStatus = null;
            for (final String relativePath : resourceRelativePaths) {
                // From ModuleReader#find(): "If the module reader can determine that the name locates a
                // directory then the resulting URI will end with a slash ('/')."  But From the documentation
                // for ModuleReader#list(): "Whether the stream of elements includes names corresponding to
                // directories in the module is module reader specific."  We don't have a way of checking if
                // a resource is a directory without trying to open it, unless ModuleReader#list() also decides
                // to put a "/" on the end of resource paths corresponding to directories. Skip directories if
                // they are found, but if they are not able to be skipped, we will have to settle for having
                // some IOExceptions thrown when directories are mistaken for files when trying to call a
                // FileMatchProcessor on a directory path that matches a given path criterion.
                if (relativePath.endsWith("/")) {
                    continue;
                }

                // Get match status of the parent directory of this resource's relative path (or reuse the last
                // match status for speed, if the directory name hasn't changed).
                final int lastSlashIdx = relativePath.lastIndexOf("/");
                final String parentRelativePath = lastSlashIdx < 0 ? "/"
                        : relativePath.substring(0, lastSlashIdx + 1);
                final boolean parentRelativePathChanged = !parentRelativePath.equals(prevParentRelativePath);
                final ScanSpecPathMatch parentMatchStatus = //
                        prevParentRelativePath == null || parentRelativePathChanged
                                ? scanSpec.dirWhitelistMatchStatus(parentRelativePath)
                                : prevParentMatchStatus;
                prevParentRelativePath = parentRelativePath;
                prevParentMatchStatus = parentMatchStatus;

                // Class can only be scanned if it's within a whitelisted path subtree, or if it is a classfile
                // that has been specifically-whitelisted
                if (parentMatchStatus != ScanSpecPathMatch.HAS_WHITELISTED_PATH_PREFIX
                        && parentMatchStatus != ScanSpecPathMatch.AT_WHITELISTED_PATH
                        && (parentMatchStatus != ScanSpecPathMatch.AT_WHITELISTED_CLASS_PACKAGE
                                || !scanSpec.isSpecificallyWhitelistedClass(relativePath))) {
                    continue;
                }

                final LogNode subLog = log == null ? null
                        : log.log(relativePath, "Found whitelisted file: " + relativePath);

                // Store relative paths of any classfiles encountered
                if (FileUtils.isClassfile(relativePath)) {
                    classfileMatches.add(newClasspathResource(relativePath));
                }

                // Match file paths against path patterns
                for (final FileMatchProcessorWrapper fileMatchProcessorWrapper : //
                scanSpec.getFileMatchProcessorWrappers()) {
                    if (fileMatchProcessorWrapper.filePathMatches(relativePath, subLog)) {
                        // File's relative path matches.
                        fileMatches.put(fileMatchProcessorWrapper, newClasspathResource(relativePath));
                    }
                }
                // Last modified time is not tracked for modules, since even if the file changes,
                // module path scanning won't pick up the module changes until the module loader
                // reloads the module, and we have no way of tracking when that happens.
                //                if (!moduleRef.isSystemModule()) {
                //                    File moduleFile = moduleRef.getModuleLocationFile();
                //                    fileToLastModified.put(moduleFile, moduleFile.lastModified());
                //                }
            }
        }
    }

    /** Close and free all open ZipFiles. */
    @Override
    public void close() {
        if (moduleReaderProxyRecycler != null) {
            moduleReaderProxyRecycler.close();
        }
        moduleReaderProxyRecycler = null;
    }
}
