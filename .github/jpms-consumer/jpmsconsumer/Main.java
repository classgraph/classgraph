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
import io.github.classgraph.base.ClassGraphLog;
import io.github.classgraph.base.LogNode;
import io.github.classgraph.classpath.ModulePathInfo;
import io.github.classgraph.vfs.CloseableByteBuffer;
import io.github.classgraph.vfs.VfsEntry;
import io.github.classgraph.viz.GraphVizDotFile;

/**
 * Scans from inside a module on the module path. The unit tests all run on the classpath, so without this the
 * module descriptors would only ever be checked by the compiler, and a wrong {@code requires} or {@code exports}
 * would not be found until someone put the published jars on a module path.
 *
 * <p>
 * The module requires only {@code io.github.classgraph.viz}, and this class names a type from each of the five
 * modules, so a {@code requires transitive} that is missing anywhere in the chain fails the compile with an error
 * that names the package that cannot be read.
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
        // io.github.classgraph.base is read only through the requires transitive chain from viz
        final LogNode log = new LogNode();
        final ClassGraphLog child = log.log("logged from a module");
        check("a log from the base module can be written",
                child != null && log.toString().contains("logged from a module"));

        // io.github.classgraph.classpath is read only through the requires transitive chain from viz
        final ModulePathInfo modulePathInfo = new ClassGraph().getModulePathInfo();
        check("the module path given on the command line is reported", !modulePathInfo.getModulePath().isEmpty());

        // Scanning this module requires the module path to be read, and the module to be opened to ClassGraph.
        // Nothing here is on the classpath, so it is enableNonSystemModules() that reaches it, not
        // enableClasspath().
        try (ScanResult scanResult = new ClassGraph().enableNonSystemModules().enableClassInfo().enableFieldInfo()
                .enableMethodInfo().enableAnnotationInfo().enableStaticFinalFieldConstantInitializerValues()
                .ignoreClassVisibility().ignoreFieldVisibility().ignoreMethodVisibility()
                .acceptPackages("jpmsconsumer").scan()) {
            check("the module's own classes are scanned",
                    scanResult.getAllClasses().getNames().contains("jpmsconsumer.Main"));
            check("a nested class in the module is scanned",
                    scanResult.getClassInfo("jpmsconsumer.Main$Widget") != null);
            // io.github.classgraph.viz is required directly, so this also resolves it as a module at runtime
            check("the viz module draws the module's classes",
                    GraphVizDotFile.generate(scanResult, scanResult.getAllClasses()).contains("Widget"));
        }

        // Resources have to be read out of the jars that are on the module path, not just classes.
        try (ScanResult scanResult = new ClassGraph().enableNonSystemModules().acceptPaths("META-INF").scan()) {
            final Resource manifest = scanResult.getResourcesWithLeafName("MANIFEST.MF").stream().findFirst()
                    .orElse(null);
            check("a manifest is found on the module path", manifest != null);
            check("the manifest can be read",
                    manifest != null && manifest.loadAsString().startsWith("Manifest-Version"));
            if (manifest != null) {
                // io.github.classgraph.vfs is read only through the requires transitive chain from viz
                final VfsEntry vfsEntry = manifest.getVfsEntry();
                check("the manifest's VFS entry is reachable", vfsEntry.getLastSegment().equals("MANIFEST.MF"));
                try (CloseableByteBuffer buffer = manifest.read()) {
                    check("the manifest can be read as a buffer",
                            buffer.getByteBuffer() != null && buffer.getByteBuffer().remaining() > 0);
                }
            }
        }

        // System modules are read out of the jrt filesystem, which is a different VFS root kind again.
        try (ScanResult scanResult = new ClassGraph().enableNonSystemModules().acceptPackages("java.util.function")
                .enableClassInfo().scan()) {
            check("system modules are skipped unless they are asked for",
                    scanResult.getClassInfo("java.util.function.Function") == null);
        }
        try (ScanResult scanResult = new ClassGraph().enableSystemJars().enableSystemModules()
                .acceptPackages("java.util.function").enableClassInfo().scan()) {
            final ClassInfo function = scanResult.getClassInfo("java.util.function.Function");
            check("a system module is scanned when it is asked for", function != null);
            check("the class read out of the system module is right", function != null && function.isInterface());
        }
        System.out.println("All module path checks passed.");
    }
}
