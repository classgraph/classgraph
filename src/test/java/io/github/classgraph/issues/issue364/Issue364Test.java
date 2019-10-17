/*
 * This file is part of ClassGraph.
 *
 * Author: James Ward
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
package io.github.classgraph.issues.issue364;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.attribute.PosixFilePermission;

import org.junit.jupiter.api.Test;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * Issue364Test.
 */
public class Issue364Test {

    /**
     * Test No Permissions.
     */
    @Test
    public void testNoPermissions() {
        final ClassLoader classLoader = Issue364Test.class.getClassLoader();
        final String aJarName = "issue364-no-permissions.jar";
        final URL aJarURL = classLoader.getResource(aJarName);
        final URLClassLoader overrideClassLoader = new URLClassLoader(new URL[] { aJarURL });

        try (ScanResult result = new ClassGraph().overrideClassLoaders(overrideClassLoader)
                .ignoreParentClassLoaders().scan()) {
            assertThat(result.getResourcesWithPath("META-INF/resources/webjars/permissions-jar/1.0.0/bin/all")
                    .get(0).getLastModified()).isEqualTo(1434543812000L);
            assertThat(result.getResourcesWithPath("META-INF/resources/webjars/permissions-jar/1.0.0/bin/all")
                    .get(0).getPosixFilePermissions()).isNull();
            assertThat(result
                    .getResourcesWithPath("META-INF/resources/webjars/permissions-jar/1.0.0/bin/groupreadwrite")
                    .get(0).getLastModified()).isEqualTo(1434557162000L);
            assertThat(result
                    .getResourcesWithPath("META-INF/resources/webjars/permissions-jar/1.0.0/bin/groupreadwrite")
                    .get(0).getPosixFilePermissions()).isNull();
            assertThat(result
                    .getResourcesWithPath("META-INF/resources/webjars/permissions-jar/1.0.0/bin/owneronlyread")
                    .get(0).getLastModified()).isEqualTo(1434557150000L);
            assertThat(result
                    .getResourcesWithPath("META-INF/resources/webjars/permissions-jar/1.0.0/bin/owneronlyread")
                    .get(0).getPosixFilePermissions()).isNull();
            assertThat(result
                    .getResourcesWithPath("META-INF/resources/webjars/permissions-jar/1.0.0/bin/ownerreadwrite")
                    .get(0).getLastModified()).isEqualTo(1434543812000L);
            assertThat(result
                    .getResourcesWithPath("META-INF/resources/webjars/permissions-jar/1.0.0/bin/ownerreadwrite")
                    .get(0).getPosixFilePermissions()).isNull();
        }
    }

    /**
     * Test Permissions.
     */
    @Test
    public void testPermissions() {
        final ClassLoader classLoader = Issue364Test.class.getClassLoader();
        final String aJarName = "issue364-permissions.jar";
        final URL aJarURL = classLoader.getResource(aJarName);
        final URLClassLoader overrideClassLoader = new URLClassLoader(new URL[] { aJarURL });

        try (ScanResult result = new ClassGraph().overrideClassLoaders(overrideClassLoader)
                .ignoreParentClassLoaders().scan()) {
            assertThat(result.getResourcesWithPath("META-INF/resources/webjars/permissions-jar/1.0.0/bin/all")
                    .get(0).getLastModified()).isEqualTo(1434543812000L);
            assertThat(result.getResourcesWithPath("META-INF/resources/webjars/permissions-jar/1.0.0/bin/all")
                    .get(0).getPosixFilePermissions()).containsOnly(PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE,
                            PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
                            PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE);

            assertThat(result.getResourcesWithPath("META-INF/resources/webjars/permissions-jar/1.0.0/bin/execute")
                    .get(0).getLastModified()).isEqualTo(1434557130000L);
            assertThat(result.getResourcesWithPath("META-INF/resources/webjars/permissions-jar/1.0.0/bin/execute")
                    .get(0).getPosixFilePermissions()).containsOnly(PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE);

            assertThat(result
                    .getResourcesWithPath("META-INF/resources/webjars/permissions-jar/1.0.0/bin/groupreadwrite")
                    .get(0).getLastModified()).isEqualTo(1434557162000L);
            assertThat(result
                    .getResourcesWithPath("META-INF/resources/webjars/permissions-jar/1.0.0/bin/groupreadwrite")
                    .get(0).getPosixFilePermissions()).containsOnly(PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_READ,
                            PosixFilePermission.GROUP_WRITE);

            assertThat(result
                    .getResourcesWithPath("META-INF/resources/webjars/permissions-jar/1.0.0/bin/owneronlyread")
                    .get(0).getLastModified()).isEqualTo(1434557152000L);
            assertThat(result
                    .getResourcesWithPath("META-INF/resources/webjars/permissions-jar/1.0.0/bin/owneronlyread")
                    .get(0).getPosixFilePermissions()).containsOnly(PosixFilePermission.OWNER_READ);

            assertThat(result
                    .getResourcesWithPath("META-INF/resources/webjars/permissions-jar/1.0.0/bin/ownerreadwrite")
                    .get(0).getLastModified()).isEqualTo(1434543812000L);
            assertThat(result
                    .getResourcesWithPath("META-INF/resources/webjars/permissions-jar/1.0.0/bin/ownerreadwrite")
                    .get(0).getPosixFilePermissions()).containsOnly(PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_READ,
                            PosixFilePermission.OTHERS_READ);
        }
    }
}
