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
package io.github.classgraph.issues.issue141;

/**
 * Issue141Test.
 *
 * @author wuetherich
 */
public class Issue141Test {
    // Disabled because ClassGraph no longer stops if an invalid classfile is found (the classfile is simply skipped)

    //    @Test
    //    public void issue141Test() throws IOException {
    //        // resolve and download org.ow2.asm:asm:6.0_BETA (which contains a module-info.class)
    //        final File resolvedFile = MavenResolvers.createMavenResolver(null, null).resolve("org.ow2.asm", "asm", null,
    //                null, "6.0_BETA");
    //        assertThat(resolvedFile).isFile();
    //
    //        // create a new custom class loader
    //        final ClassLoader classLoader = new URLClassLoader(new URL[] { resolvedFile.toURI().toURL() }, null);
    //
    //        // scan the classpath
    //        try (ScanResult scanResult = new ClassGraph().overrideClassLoaders(classLoader).enableClassInfo().scan()) {
    //        }
    //    }
}
