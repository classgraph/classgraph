package io.github.lukehutch.fastclasspathscanner.test.classloaderfiltered;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanResult;
import org.junit.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ClassloaderFilteredScanResultTest {
    @Test
    public void testScanClasses() throws ClassNotFoundException {
        URL testJarUrl = getClass().getResource("/classloader-filtered-test.jar");
        URLClassLoader loader = new URLClassLoader(new URL[] {testJarUrl}, getClass().getClassLoader());

        ScanResult scanResult = new FastClasspathScanner()
                .overrideClassLoaders(loader)
                .scan();

        List<String> classes = scanResult.getNamesOfAllClasses(loader);

        assertEquals(3, classes.size());
        assertTrue(classes.contains("io.github.lukehutch.fastclasspathscanner.test.classloaderfiltered.A"));
        assertTrue(classes.contains("io.github.lukehutch.fastclasspathscanner.test.classloaderfiltered.B"));
        assertTrue(classes.contains("io.github.lukehutch.fastclasspathscanner.test.classloaderfiltered.C"));
    }
}
