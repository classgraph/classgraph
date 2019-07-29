
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
import io.github.classgraph.test.whitelisted.Cls;
import io.github.classgraph.test.whitelisted.blacklistedsub.BlacklistedSub;

/**
 * DefaultPackageTest.
 */
public class DefaultPackageTest {
    /** The Constant WHITELIST_PACKAGE. */
    private static final String WHITELIST_PACKAGE = Cls.class.getPackage().getName();

    /**
     * Scan.
     */
    @Test
    public void scan() {
        try (ScanResult scanResult = new ClassGraph().whitelistPackagesNonRecursive("").scan()) {
            final List<String> allClasses = scanResult.getAllClasses().getNames();
            assertThat(allClasses).contains(DefaultPackageTest.class.getName());
            assertThat(allClasses).contains(ClassInDefaultPackage.class.getName());
            assertThat(allClasses).doesNotContain(Cls.class.getName());
            assertThat(allClasses).doesNotContain(ClassGraph.class.getName());
            assertThat(allClasses).doesNotContain(String.class.getName());
            assertThat(allClasses).doesNotContain(BlacklistedSub.class.getName());
        }
    }

    /**
     * Scan with whitelist.
     */
    @Test
    public void scanWithWhitelist() {
        try (ScanResult scanResult = new ClassGraph().whitelistPackages(WHITELIST_PACKAGE).scan()) {
            final List<String> allClasses = scanResult.getAllClasses().getNames();
            assertThat(allClasses).doesNotContain(DefaultPackageTest.class.getName());
            assertThat(allClasses).contains(BlacklistedSub.class.getName());
            assertThat(allClasses).contains(Cls.class.getName());
            assertThat(allClasses).doesNotContain(ClassGraph.class.getName());
            assertThat(allClasses).doesNotContain(String.class.getName());
            assertThat(allClasses).doesNotContain(ClassInDefaultPackage.class.getName());
        }
    }
}
