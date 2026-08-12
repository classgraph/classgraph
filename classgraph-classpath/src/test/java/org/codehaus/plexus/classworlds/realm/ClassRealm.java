package org.codehaus.plexus.classworlds.realm;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.SortedSet;
import java.util.TreeSet;

import org.jspecify.annotations.Nullable;

/**
 * Stand-in for Plexus ClassWorlds' {@code ClassRealm}, the classloader that Maven loads a plugin's classes with. A
 * realm loads classes from its own jars, from the realms it imports packages from, and from its parent realm, in an
 * order that its strategy decides.
 */
public class ClassRealm extends URLClassLoader {
    /** The strategy that decides whether the realm or its parent is asked for a class first. */
    public @Nullable Object strategy;

    /** The packages that this realm imports from other realms. */
    public @Nullable SortedSet<Entry> foreignImports;

    /** The parent realm, which is not necessarily this classloader's parent. */
    private @Nullable ClassLoader parentClassLoader;

    /**
     * Constructor.
     *
     * @param urls
     *            the realm's own jars.
     */
    public ClassRealm(final URL... urls) {
        this(/* parent = */ null, urls);
    }

    /**
     * Constructor for a realm that also has a parent classloader, which is not the same thing as its parent realm.
     *
     * @param parent
     *            the parent classloader.
     * @param urls
     *            the realm's own jars.
     */
    public ClassRealm(final @Nullable ClassLoader parent, final URL... urls) {
        super(urls, parent);
    }

    /**
     * Set the strategy that decides whether the realm or its parent is asked for a class first.
     *
     * @param strategy
     *            the strategy.
     * @return this, for chaining.
     */
    public ClassRealm withStrategy(final Object strategy) {
        this.strategy = strategy;
        return this;
    }

    /**
     * Import a package from another realm.
     *
     * @param packageName
     *            the package to import.
     * @param fromRealm
     *            the realm to import it from.
     * @return this, for chaining.
     */
    public ClassRealm importingFrom(final String packageName, final ClassLoader fromRealm) {
        if (foreignImports == null) {
            foreignImports = new TreeSet<>();
        }
        foreignImports.add(new Entry(packageName, fromRealm));
        return this;
    }

    /**
     * Set the parent realm.
     *
     * @param parentClassLoader
     *            the parent realm.
     * @return this, for chaining.
     */
    public ClassRealm withParentRealm(final ClassLoader parentClassLoader) {
        this.parentClassLoader = parentClassLoader;
        return this;
    }

    /**
     * The parent realm.
     *
     * @return the parent realm, or null if the realm has none.
     */
    public @Nullable ClassLoader getParentClassLoader() {
        return parentClassLoader;
    }
}
