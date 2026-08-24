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
package io.github.classgraph.issues.issue141;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.classgraph.ClassGraph;

/**
 * A classfile that cannot be parsed does not stop the scan -- it is skipped, and every other class in the same
 * classpath element is still returned.
 *
 * @author wuetherich
 */
public class Issue141Test {
    /** The package directory that both classfiles are written to. */
    private static final String PKG_DIR = "io/github/classgraph/issues/issue141";

    /** A class to copy into the scanned directory as a valid classfile. */
    public static class Good {
    }

    /**
     * Scan a directory containing the classfile of {@link Good} and an unparseable classfile alongside it.
     *
     * @param tempDir
     *            the directory to write the two classfiles to.
     * @throws IOException
     *             if the classfiles could not be written.
     */
    @Test
    void unparseableClassfileIsSkippedRatherThanStoppingTheScan(@TempDir final Path tempDir) throws IOException {
        final var pkgDir = Files.createDirectories(tempDir.resolve(PKG_DIR));
        final var goodClassfileName = Good.class.getName().substring(Good.class.getName().lastIndexOf('.') + 1)
                + ".class";
        try (var goodClassfile = Good.class.getResourceAsStream(goodClassfileName)) {
            assertThat(goodClassfile).as("classfile of " + Good.class.getName()).isNotNull();
            Files.write(pkgDir.resolve(goodClassfileName), goodClassfile.readAllBytes());
        }
        // Not a classfile at all -- it does not even start with the 0xCAFEBABE magic number
        Files.write(pkgDir.resolve("Bad.class"), "This is not a classfile".getBytes(StandardCharsets.UTF_8));

        try (var scanResult = new ClassGraph().enableClasspathEntries(tempDir.toString()).enableClassInfo()
                .scan()) {
            assertThat(scanResult.getAllClasses().getNames()).containsExactly(Good.class.getName());
        }
    }
}
