
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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import io.github.classgraph.test.accepted.Cls;
import io.github.classgraph.test.accepted.rejectedsub.RejectedSub;

/**
 * DisableRecursiveScanningTest.
 */
public class DisableRecursiveScanningTest {
    /** The Constant PKG. */
    private static final String PKG = Cls.class.getPackage().getName();

    /**
     * Non root package.
     */
    @Test
    public void nonRootPackage() {
        try (ScanResult scanResult = new ClassGraph().acceptPackagesNonRecursive(PKG).scan()) {
            final List<String> allClasses = scanResult.getAllClasses().getNames();
            assertThat(allClasses).contains(Cls.class.getName());
            assertThat(allClasses).doesNotContain(RejectedSub.class.getName());
        }
    }

    /**
     * Root package.
     */
    @Test
    public void rootPackage() {
        try (ScanResult scanResult = new ClassGraph().acceptPackagesNonRecursive("").scan()) {
            final List<String> allClasses = scanResult.getAllClasses().getNames();
            assertThat(allClasses).contains(ClassInDefaultPackage.class.getName());
            assertThat(allClasses).doesNotContain(Cls.class.getName());
        }
    }
}
