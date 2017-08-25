package io.github.lukehutch.fastclasspathscanner.test.classloaderfiltered;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanResult;
import org.junit.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class IndirectInheritedClassScanResultTest {
    @Test
    public void testLoadFromDifferentClassLoaders() {
        URL testJarAUrl = getClass().getResource("/indirect-dep-test-a.jar");
        URL testJarBUrl = getClass().getResource("/indirect-dep-test-b.jar");
        URL testJarCUrl = getClass().getResource("/indirect-dep-test-c.jar");

        URLClassLoader parentLoader = new URLClassLoader(new URL[] {testJarAUrl, testJarBUrl},
                getClass().getClassLoader());

        URLClassLoader childLoader = new URLClassLoader(new URL[] {testJarCUrl}, parentLoader);

        ScanResult scanResult = new FastClasspathScanner()
                .overrideClasspath(testJarCUrl.toString())  // Method 1
                //.addClassLoader(childLoader)              // Method 2
                //.overrideClassLoaders(childLoader)        // Method 3
                .scan();

        List<String> classes = scanResult.getNamesOfSubclassesOf("io.github.lukehutch.fastclasspathscanner.test.classloaderfiltered.A");

        assertEquals(1, classes.size());
        assertTrue(classes.contains("io.github.lukehutch.fastclasspathscanner.test.classloaderfiltered.C"));
    }
}
