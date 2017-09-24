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

import io.github.lukehutch.fastclasspathscanner.utils.InterruptionChecker;
import io.github.lukehutch.fastclasspathscanner.utils.LogNode;
import io.github.lukehutch.fastclasspathscanner.utils.NestedJarHandler;
import io.github.lukehutch.fastclasspathscanner.utils.SingletonMap;
import io.github.lukehutch.fastclasspathscanner.utils.WorkQueue;

/** A map from relative path to classpath element singleton. */
class RelativePathToElementMap extends SingletonMap<RelativePath, ClasspathElement> implements AutoCloseable {
    private final boolean scanFiles;
    private final ScanSpec scanSpec;
    private final NestedJarHandler nestedJarHandler;
    private final InterruptionChecker interruptionChecker;
    private final LogNode log;
    private WorkQueue<RelativePath> workQueue;

    /** A map from relative path to classpath element singleton. */
    RelativePathToElementMap(final boolean scanFiles, final ScanSpec scanSpec,
            final NestedJarHandler nestedJarHandler, final InterruptionChecker interruptionChecker,
            final LogNode log) {
        this.scanFiles = scanFiles;
        this.scanSpec = scanSpec;
        this.nestedJarHandler = nestedJarHandler;
        this.interruptionChecker = interruptionChecker;
        this.log = log;
    }

    /**
     * Work queue -- needs to be set for zipfiles, but not for directories, since zipfiles can contain Class-Path
     * manifest entries, which require the adding of additional work units to the scanning work queue.
     */
    public void setWorkQueue(final WorkQueue<RelativePath> workQueue) {
        this.workQueue = workQueue;
    }

    /** Create a new classpath element singleton instance. */
    @Override
    public ClasspathElement newInstance(final RelativePath classpathElt) {
        return ClasspathElement.newInstance(classpathElt, scanFiles, scanSpec, nestedJarHandler, workQueue,
                interruptionChecker, log);
    }

    /** Close the classpath elements. */
    @Override
    public void close() throws Exception {
        for (final ClasspathElement classpathElt : values()) {
            classpathElt.close();
        }
    }
}
