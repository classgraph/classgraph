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
package io.github.lukehutch.fastclasspathscanner.issues.issue183;

import static org.assertj.core.api.StrictAssertions.assertThat;

import java.io.IOException;

import org.junit.Test;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.FileMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanResult;

public class Issue183Test {
    @Test
    public void testIOExceptionThrown() {
        try {
            new FastClasspathScanner(Issue183Test.class.getPackage().getName()) //
                    .disableRecursiveScanning()
                    .matchFilenamePattern(".*", (FileMatchProcessor) (relativePath, inputStream, lengthBytes) -> {
                        throw new IOException("bombed");
                    }).scan();
            throw new RuntimeException("No exception thrown");
        } catch (final Exception e) {
            assertThat(e.getCause().toString()).isEqualTo("java.io.IOException: bombed");
        }
    }

    @Test
    public void testSuppressMatchProcessorExceptions() {
        ScanResult scanResult = null;
        try {
            scanResult = new FastClasspathScanner(Issue183Test.class.getPackage().getName()) //
                    .disableRecursiveScanning() //
                    .suppressMatchProcessorExceptions()
                    .matchFilenamePattern(".*", (FileMatchProcessor) (relativePath, inputStream, lengthBytes) -> {
                        throw new IOException("bombed");
                    }).scan();
        } catch (final Exception e) {
            throw new RuntimeException("Exception should not have been thrown");
        }
        assertThat(scanResult.getMatchProcessorExceptions().get(0).toString())
                .isEqualTo("java.io.IOException: bombed");
    }
}
