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
package jpmsconsumer;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.Resource;
import io.github.classgraph.ScanResult;

/**
 * Scans from inside a module on the module path. The unit tests all run on the classpath, so without this the
 * module descriptors would only ever be checked by the compiler, and a wrong {@code requires} or {@code exports}
 * would not be found until someone put the published jars on a module path.
 */
public final class Main {
    /** A nested class, so that the scan of this module has to find more than the one class. */
    public static final class Widget {
    }

    /** Cannot be constructed. */
    private Main() {
    }

    /**
     * Check a condition, and fail the run if it does not hold.
     *
     * @param what
     *            what was checked.
     * @param ok
     *            whether the check passed.
     */
    private static void check(final String what, final boolean ok) {
        System.out.println((ok ? "ok   : " : "FAIL : ") + what);
        if (!ok) {
            throw new IllegalStateException("Check failed: " + what);
        }
    }

    /**
     * Run the checks.
     *
     * @param args
     *            ignored.
     * @throws Exception
     *             if a resource cannot be read.
     */
    public static void main(final String[] args) throws Exception {
        // Scanning this module requires the module path to be read, and the module to be opened to ClassGraph.
        try (ScanResult scanResult = new ClassGraph().enableAllInfo().acceptPackages("jpmsconsumer").scan()) {
            check("the module's own classes are scanned",
                    scanResult.getAllClasses().getNames().contains("jpmsconsumer.Main"));
            check("a nested class in the module is scanned",
                    scanResult.getClassInfo("jpmsconsumer.Main$Widget") != null);
        }

        // Resources have to be read out of the jars that are on the module path, not just classes.
        try (ScanResult scanResult = new ClassGraph().acceptPaths("META-INF").scan()) {
            final Resource manifest = scanResult.getResourcesWithLeafName("MANIFEST.MF").stream().findFirst()
                    .orElse(null);
            check("a manifest is found on the module path", manifest != null);
            check("the manifest can be read",
                    manifest != null && manifest.getContentAsString().startsWith("Manifest-Version"));
        }

        // System modules are read out of the jrt filesystem, which is a different VFS root kind again.
        try (ScanResult scanResult = new ClassGraph().acceptPackages("java.util.function").enableClassInfo()
                .scan()) {
            check("system modules are skipped unless they are asked for",
                    scanResult.getClassInfo("java.util.function.Function") == null);
        }
        try (ScanResult scanResult = new ClassGraph().enableSystemJarsAndModules()
                .acceptPackages("java.util.function").scan()) {
            final ClassInfo function = scanResult.getClassInfo("java.util.function.Function");
            check("a system module is scanned when it is asked for", function != null);
            check("the class read out of the system module is right", function != null && function.isInterface());
        }
        System.out.println("All module path checks passed.");
    }
}
