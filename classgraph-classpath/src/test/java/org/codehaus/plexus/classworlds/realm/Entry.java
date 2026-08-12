package org.codehaus.plexus.classworlds.realm;

/**
 * Stand-in for Plexus ClassWorlds' {@code Entry}, one package that a realm imports from another realm. Entries are
 * held in a sorted set, so they are ordered by the package they import.
 */
public class Entry implements Comparable<Entry> {
    /** The package that is imported. */
    private final String packageName;

    /** The realm that the package is imported from. */
    private final ClassLoader classLoader;

    /**
     * Constructor.
     *
     * @param packageName
     *            the package that is imported.
     * @param classLoader
     *            the realm that the package is imported from.
     */
    public Entry(final String packageName, final ClassLoader classLoader) {
        this.packageName = packageName;
        this.classLoader = classLoader;
    }

    /**
     * The realm that the package is imported from.
     *
     * @return the realm.
     */
    public ClassLoader getClassLoader() {
        return classLoader;
    }

    @Override
    public int compareTo(final Entry other) {
        return packageName.compareTo(other.packageName);
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof final Entry entry && packageName.equals(entry.packageName);
    }

    @Override
    public int hashCode() {
        return packageName.hashCode();
    }
}
