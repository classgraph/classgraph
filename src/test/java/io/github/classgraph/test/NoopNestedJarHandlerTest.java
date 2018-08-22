/*
 * This file is part of ClassGraph.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/lukehutch/fast-classpath-scanner
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2018 Luke Hutchison
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
package io.github.classgraph.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.net.URLClassLoader;

import javax.persistence.Entity;

import org.junit.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;
import io.github.classgraph.ClassInfoList.ClassInfoFilter;
import io.github.classgraph.issues.issue209.Issue209Test;
import io.github.classgraph.issues.issue216.Issue216Test;
import io.github.classgraph.issues.issue99.Issue99Test;
import io.github.classgraph.utils.NoopNestedJarHandler;

public class NoopNestedJarHandlerTest {
    
    /**
     * Borrowed from {@link Issue216Test#testSpringBootJarWithLibJars()} to exercise
     * {@link NoopNestedJarHandler#getInnermostNestedJar(String, LogNode)} and
     * {@link NoopNestedJarHandler#getJarfileMetadataReader(File, String, LogNode)}
     */
    @Test
    public void testSpringBootJarWithLibJarsWithWriteSkip() {
        final ScanResult result = new ClassGraph().whitelistPackages(Issue216Test.class.getPackage().getName())
                .enableAllInfo()
                .skipAllWriteOperations()
                .scan();
        
        assertThat(result.getAllClasses().filter(new ClassInfoFilter() {
            @Override
            public boolean accept(final ClassInfo ci) {
                return ci.hasAnnotation(Entity.class.getName());
            }
        }).getNames()).containsOnly(Issue216Test.class.getName());
    }

    /**
     * Borrowed from {@link Issue209Test#testSpringBootJarWithLibJarsUsingCustomClassLoader()} to exercise
     * {@link NoopNestedJarHandler#unzipToTempDir(File, String, LogNode)}
     */
    @Test
    public void testSpringBootJarWithLibJarsUsingCustomClassLoaderWithWriteSkip() {
        final ScanResult result = new ClassGraph().whitelistPackages(
                "org.springframework.boot.loader.util", "com.foo", "issue209lib")
                .overrideClassLoaders(new URLClassLoader(
                        new URL[] { Issue209Test.class.getClassLoader().getResource("issue209.jar") }))
                .createClassLoaderForMatchingClasses()
                .skipAllWriteOperations()
                .scan();
        
        assertThat(result.getAllClasses().getNames()).isEmpty();
    }
    
    /**
     * Borrowed from {@link Issue99Test#issue99Test()} to exercise
     * {@link NoopNestedJarHandler#getZipFileRecycler(File, LogNode)}
     */
    @Test
    public void issue99TestWithWriteSkip() {
        final String jarPath = Issue99Test.class.getClassLoader().getResource("nested-jars-level1.zip").getPath()
                + "!level2.jar!level3.jar!classpath1/classpath2";
        
        ScanResult result = new ClassGraph().overrideClasspath(jarPath).enableClassInfo().skipAllWriteOperations().scan(); 
        assertThat(result.getAllClasses().getNames()).isEmpty();
        
        result = new ClassGraph().overrideClasspath(jarPath).enableClassInfo().blacklistJars("level3.jar").skipAllWriteOperations().scan(); 
        assertThat(result.getAllClasses().getNames()).isEmpty();
    }
    
    /*
     * Couldn't find tests that exercise:
     * - getOutermostJar
     * - getModuleReaderProxyRecycler
     */
}
