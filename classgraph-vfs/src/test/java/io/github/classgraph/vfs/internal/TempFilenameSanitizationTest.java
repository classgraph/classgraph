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
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the
 * Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions
 * of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED
 * TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
 * THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
 * CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */
package io.github.classgraph.vfs.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.classgraph.base.internal.concurrency.InterruptionChecker;
import io.github.classgraph.vfs.internal.spec.VfsScanSpec;

/**
 * Tests that a temporary file can be created for a nested jar whose entry name contains characters that are legal
 * in a zip entry name but not in a filename. A nested jar that is too large to be extracted to RAM is spilled to a
 * temporary file named after the zip entry it came from, so every such character has to be replaced first.
 */
class TempFilenameSanitizationTest {
    /**
     * Every ASCII character that Windows rejects in a filename (the control characters, and {@code " * / < > ? \
     * |}), plus {@code :}, which Windows accepts but treats as the start of an NTFS alternate data stream. Linux
     * and macOS reject only {@code /}. (Measured on real GitHub Actions runners for all three platforms.)
     */
    private static final String UNSAFE_CHARS = "\b\t\n\f\r\"*/:<>?\\|";

    /** A nested jar whose entry name is not a valid filename must still get a temporary file. */
    @Test
    void unsafeCharactersInEntryNameAreReplaced() throws Exception {
        final var owner = ScanResources.open(new VfsScanSpec(), new InterruptionChecker());
        try {
            final var tempFile = owner.resources().makeTempFile("BOOT-INF/lib/na" + UNSAFE_CHARS + "me.jar",
                    /* onlyUseLeafname = */ false);
            assertThat(tempFile).exists();
            for (var i = 0; i < UNSAFE_CHARS.length(); i++) {
                assertThat(tempFile.getName()).doesNotContain(UNSAFE_CHARS.substring(i, i + 1));
            }
        } finally {
            owner.close(/* log = */ null);
        }
    }
}
