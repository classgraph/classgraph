/*
 * This file is part of ClassGraph.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/lukehutch/fast-classpath-scanner
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2018 Luke Hutchison
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
package io.github.classgraph.utils;

import java.io.File;
import java.io.IOException;
import java.util.Set;
import java.util.Map.Entry;
import java.util.zip.ZipFile;

import io.github.classgraph.ModuleReaderProxy;
import io.github.classgraph.ModuleRef;

public interface NestedJarHandler {

    /**
     * Get a ZipFile recycler given the (non-nested) canonical path of a jarfile.
     * 
     * @param zipFile
     *            The zipfile.
     * @param log
     *            The log.
     * @return The ZipFile recycler.
     * @throws Exception
     *             If the zipfile could not be opened.
     */
    Recycler<ZipFile, IOException> getZipFileRecycler(File zipFile, LogNode log) throws Exception;

    /**
     * Get a {@link JarfileMetadataReader} singleton for a given jarfile (so that the manifest and ZipEntries will
     * only be read once).
     * 
     * @param zipFile
     *            The zipfile.
     * @param jarfilePackageRoot
     *            The package root within the zipfile.
     * @param log
     *            The log.
     * @return The {@link JarfileMetadataReader}.
     * @throws Exception
     *             If the zipfile could not be opened.
     */
    JarfileMetadataReader getJarfileMetadataReader(File zipFile, String jarfilePackageRoot, LogNode log)
            throws Exception;

    /**
     * Get a ModuleReaderProxy recycler given a ModuleRef.
     * 
     * @param moduleRef
     *            The {@link ModuleRef}.
     * @param log
     *            The log.
     * @return The ModuleReaderProxy recycler.
     * @throws Exception
     *             If the module could not be opened.
     */
    Recycler<ModuleReaderProxy, IOException> getModuleReaderProxyRecycler(ModuleRef moduleRef, LogNode log)
            throws Exception;

    /**
     * Get a File for a given (possibly nested) jarfile path, unzipping the first N-1 segments of an N-segment
     * '!'-delimited path to temporary files, then returning the File reference for the N-th temporary file.
     *
     * <p>
     * If the path does not contain '!', returns the File represented by the path.
     *
     * <p>
     * All path segments should end in a jarfile extension, e.g. ".jar" or ".zip".
     * 
     * @param nestedJarPath
     *            The nested jar path.
     * @param log
     *            The log.
     * @return An {@code Entry<File, Set<String>>}, where the {@code File} is the innermost jar, and the
     *         {@code Set<String>} is the set of all relative paths of scanning roots within the innermost jar (may
     *         be empty, or may contain strings like "target/classes" or similar). If there was an issue with the
     *         path, returns null.
     * @throws Exception
     *             If the innermost jarfile could not be extracted.
     */
    Entry<File, Set<String>> getInnermostNestedJar(String nestedJarPath, LogNode log) throws Exception;

    /**
     * Given a File reference for an inner nested jarfile, find the outermost jarfile it was extracted from.
     * 
     * @param jarFile
     *            The jarfile.
     * @return The outermost jar that the jarfile was contained within.
     */
    File getOutermostJar(File jarFile);

    /**
     * Unzip a given package root within a zipfile to a temporary directory, starting several more threads to
     * perform the unzip in parallel, then return the temporary directory. The temporary directory and all of its
     * contents will be removed when {@code NestedJarHandler#close())} is called.
     * 
     * <p>
     * N.B. standalone code for parallel unzip can be found at https://github.com/lukehutch/quickunzip
     * 
     * @param jarFile
     *            The jarfile.
     * @param packageRoot
     *            The package root to extract from the jar.
     * @param log
     *            The log.
     * @return The {@link File} object for the temporary directory the package root was extracted to.
     * @throws IOException
     *             If the package root could not be extracted from the jar.
     */
    File unzipToTempDir(File jarFile, String packageRoot, LogNode log) throws IOException;

    /**
     * Close zipfiles and modules.
     */
    void closeRecyclers();

    /**
     * Close zipfiles and modules, and delete temporary files.
     * 
     * @param log
     *            The log.
     */
    void close(LogNode log);
    
}
