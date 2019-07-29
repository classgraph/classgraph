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
 * Copyright (c) 2019 Luke Hutchison
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
package io.github.classgraph.issues.issue255;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.Resource;
import io.github.classgraph.ResourceList;
import io.github.classgraph.ResourceList.ByteArrayConsumer;
import io.github.classgraph.ScanResult;

/**
 * Issue255Test.
 */
public class Issue255Test {
    /**
     * Issue 255 test.
     */
    @Test
    public void issue255Test() {
        final String dirPath = Issue255Test.class.getClassLoader().getResource("issue255").getPath()
                + "/test%20percent%20encoding";

        try (ScanResult scanResult = new ClassGraph().overrideClasspath(dirPath).scan()) {
            final ResourceList resources = scanResult.getAllResources();
            assertThat(resources.size()).isEqualTo(1);
            resources.forEachByteArray(new ByteArrayConsumer() {
                @Override
                public void accept(final Resource resource, final byte[] byteArray) {
                    assertThat(new String(byteArray)).isEqualTo("Issue 255");
                }
            });
        }
    }
}
