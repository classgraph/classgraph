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
package io.github.lukehutch.fastclasspathscanner.scanner.matchers;

import java.io.File;
import java.io.IOException;

import io.github.lukehutch.fastclasspathscanner.scanner.ClasspathResource;
import io.github.lukehutch.fastclasspathscanner.utils.LogNode;

/** An interface called when the corresponding FilePathTester returns true. */
public class FileMatchProcessorWrapper {
    /** An interface used to test whether a file's relative path matches a given specification. */
    public interface FilePathTester {
        public boolean filePathMatches(final File classpathElt, final String relativePathStr, final LogNode log);
    }

    private final FilePathTester filePathTester;
    private final FileMatchProcessorAny fileMatchProcessor;

    public FileMatchProcessorWrapper(final FilePathTester filePathTester,
            final FileMatchProcessorAny fileMatchProcessor) {
        this.filePathTester = filePathTester;
        this.fileMatchProcessor = fileMatchProcessor;
    }

    public boolean filePathMatches(final File classpathElt, final String relativePathStr, final LogNode log) {
        return filePathTester.filePathMatches(classpathElt, relativePathStr, log);
    }

    public void processMatch(final ClasspathResource classpathResource, final LogNode log) throws IOException {
        classpathResource.processFileMatch(fileMatchProcessor, log);
    }
}