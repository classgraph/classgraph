package io.github.classgraph.features;

import io.github.classgraph.ClassGraph;

/**
 * Scans the classpath and reports whether the hidden resource was found, printing a single line of the form
 * {@code FOUND=<true|false>}. Run in a child JVM by {@link HiddenClasspathEntryTest}, since the classpath entry
 * that holds the resource can only be hidden by a JVM command line option.
 */
public final class HiddenClasspathEntryScanner {
    /** Cannot be instantiated. */
    private HiddenClasspathEntryScanner() {
    }

    /**
     * Scan the classpath, and print whether the hidden resource was found.
     *
     * @param args
     *            ignored.
     */
    public static void main(final String[] args) {
        try (var scanResult = new ClassGraph().acceptPaths(HiddenClasspathEntryTest.RESOURCE_DIR).scan()) {
            System.out.println(
                    "FOUND=" + !scanResult.getResourcesWithPath(HiddenClasspathEntryTest.RESOURCE_PATH).isEmpty());
        }
    }
}
