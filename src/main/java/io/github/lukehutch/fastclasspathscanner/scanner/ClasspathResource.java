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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import io.github.lukehutch.fastclasspathscanner.matchprocessor.FileMatchContentsProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.FileMatchContentsProcessorWithContext;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.FileMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.FileMatchProcessorWithContext;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.FilenameMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.scanner.matchers.FileMatchProcessorAny;
import io.github.lukehutch.fastclasspathscanner.utils.ClasspathUtils;
import io.github.lukehutch.fastclasspathscanner.utils.FileUtils;
import io.github.lukehutch.fastclasspathscanner.utils.LogNode;

/**
 * The combination of a classpath element and a relative path within this classpath element.
 */
public abstract class ClasspathResource {
    public final File classpathEltFile;
    public final String pathRelativeToClasspathElt;
    public final String pathRelativeToClasspathPrefix;
    public long inputStreamLength;

    protected ClasspathResource(final File classpathEltFile, final String pathRelativeToClasspathElt,
            final String pathRelativeToClasspathPrefix) {
        this.classpathEltFile = classpathEltFile;
        this.pathRelativeToClasspathElt = pathRelativeToClasspathElt;
        this.pathRelativeToClasspathPrefix = pathRelativeToClasspathPrefix;
    }

    @Override
    public String toString() {
        return ClasspathUtils.getClasspathResourceURL(classpathEltFile, pathRelativeToClasspathElt).toString();
    }

    public abstract InputStream open() throws IOException;

    public long getInputStreamLength() {
        return inputStreamLength;
    }

    public void processFileMatch(final FileMatchProcessorAny fileMatchProcessor, final LogNode log)
            throws IOException {
        if (fileMatchProcessor instanceof FilenameMatchProcessor) {
            ((FilenameMatchProcessor) fileMatchProcessor).processMatch(
                    // classpathResource.open() is not called for FilenameMatchProcessors
                    classpathEltFile, pathRelativeToClasspathPrefix);
        } else if (fileMatchProcessor instanceof FileMatchProcessor) {
            try {
                ((FileMatchProcessor) fileMatchProcessor).processMatch(pathRelativeToClasspathPrefix,
                        /* inputStream = */ open(), //
                        inputStreamLength);
            } finally {
                close();
            }
        } else if (fileMatchProcessor instanceof FileMatchProcessorWithContext) {
            try {
                ((FileMatchProcessorWithContext) fileMatchProcessor).processMatch(classpathEltFile,
                        pathRelativeToClasspathPrefix, //
                        /* inputStream = */ open(), //
                        inputStreamLength);
            } finally {
                close();
            }
        } else if (fileMatchProcessor instanceof FileMatchContentsProcessor) {
            try {
                ((FileMatchContentsProcessor) fileMatchProcessor).processMatch(pathRelativeToClasspathPrefix,
                        FileUtils.readAllBytes(/* inputStream = */ open(), //
                                inputStreamLength, log));
            } finally {
                close();
            }
        } else if (fileMatchProcessor instanceof FileMatchContentsProcessorWithContext) {
            try {
                ((FileMatchContentsProcessorWithContext) fileMatchProcessor).processMatch(classpathEltFile,
                        pathRelativeToClasspathPrefix, //
                        FileUtils.readAllBytes(/* inputStream = */ open(), //
                                inputStreamLength, log));
            } finally {
                close();
            }
        } else {
            throw new RuntimeException(
                    "Unknown FileMatchProcessor type " + fileMatchProcessor.getClass().getName());
        }
    }

    public abstract void close();
}