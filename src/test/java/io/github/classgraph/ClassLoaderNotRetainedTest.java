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
package io.github.classgraph;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLClassLoader;

import org.junit.jupiter.api.Test;

/**
 * Loading classes is the caller's responsibility in 5.x, so a scan must not keep a classloader alive: once the
 * caller drops their own reference to a classloader they supplied, it must become collectable, even while the
 * {@link ScanResult} and the {@link ClassInfo} objects it produced are still reachable.
 */
public class ClassLoaderNotRetainedTest {

    /** A class in the scanned package, used to check that the scan actually found something. */
    private static final String SCANNED_CLASS_NAME = ClassLoaderNotRetainedTest.class.getName();

    /** The package to scan, which is small enough to keep the scan quick. */
    private static final String SCANNED_PACKAGE = ClassLoaderNotRetainedTest.class.getPackageName();

    /**
     * Wait for a weakly-referenced object to be collected.
     *
     * @param ref
     *            the reference to wait on.
     * @return true if the referent was collected.
     */
    private static boolean awaitCollection(final WeakReference<?> ref) throws InterruptedException {
        for (var i = 0; i < 50 && ref.get() != null; i++) {
            System.gc();
            Thread.sleep(20);
        }
        return ref.get() == null;
    }

    /**
     * A classloader passed to {@code overrideClassLoaders()} must be collectable once the caller drops it, even
     * though the {@link ScanResult} and a {@link ClassInfo} object from the scan are still held.
     */
    @Test
    public void suppliedClassLoaderIsCollectableWhileScanResultIsStillOpen() throws Exception {
        final var codeSourceUrl = new URL[] {
                ClassLoaderNotRetainedTest.class.getProtectionDomain().getCodeSource().getLocation() };

        // Deliberately not a try-with-resources local: the reference has to be droppable before the assertions
        var classLoader = new URLClassLoader(codeSourceUrl);
        final var classLoaderRef = new WeakReference<ClassLoader>(classLoader);

        // The ClassGraph instance is not stored in a local either, since it holds the supplied classloader for as
        // long as it is alive, so that the same instance can be scanned with more than once
        final var scanResult = new ClassGraph().overrideClassLoaders(classLoader).ignoreParentClassLoaders()
                .acceptPackages(SCANNED_PACKAGE).scan();
        try {
            final var classInfo = scanResult.getClassInfo(SCANNED_CLASS_NAME);
            assertThat(classInfo).as("the scan should have found " + SCANNED_CLASS_NAME).isNotNull();
            assertThat(classInfo.getClassLoader())
                    .as("while the caller still holds the classloader, it should be reported")
                    .isSameAs(classLoader);

            // Drop the test's own reference. The ScanResult and the ClassInfo object stay reachable.
            classLoader.close();
            classLoader = null;

            assertThat(awaitCollection(classLoaderRef)).as("the scan must not keep the supplied classloader alive")
                    .isTrue();
            assertThat(classInfo.getClassLoader())
                    .as("once collected, the classloader must be reported as unknown rather than resurrected")
                    .isNull();
        } finally {
            scanResult.close();
        }
    }
}
