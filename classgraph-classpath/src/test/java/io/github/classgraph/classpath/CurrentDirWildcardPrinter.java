package io.github.classgraph.classpath;

/**
 * Clears the {@code user.dir} system property, then prints the location of each classpath element that the
 * classpath entry {@code "*"} expands to, one per line. Run in a child JVM by {@link ClasspathFinderTest}, since
 * the current directory is read only once per JVM.
 */
public final class CurrentDirWildcardPrinter {
    /** Cannot be instantiated. */
    private CurrentDirWildcardPrinter() {
    }

    /**
     * Print the locations that {@code "*"} expands to when {@code user.dir} cannot be read.
     *
     * @param args
     *            ignored.
     */
    public static void main(final String[] args) {
        System.clearProperty("user.dir");
        try (var classpath = new ClasspathFinder().enableClasspathEntries("*").find()) {
            for (final String location : classpath.getLocations()) {
                System.out.println(location);
            }
        }
    }
}
