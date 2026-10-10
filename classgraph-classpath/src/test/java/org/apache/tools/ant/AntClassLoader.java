package org.apache.tools.ant;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Stand-in for Ant's {@code AntClassLoader}. Like the real class, it does not pass its parent to the
 * {@link ClassLoader} constructor, so {@link ClassLoader#getParent()} returns the system classloader rather than
 * its parent. It keeps its parent in a field of its own, along with whether its parent is searched before or after
 * its own classpath, and whether it is isolated.
 */
public class AntClassLoader extends ClassLoader {
    /** The classloader that this classloader delegates to. Like Ant's, it is private, and is read by reflection. */
    @SuppressWarnings("unused")
    private final ClassLoader parent;

    /**
     * Whether the parent is searched before this classloader's classpath, rather than after it. Like Ant's, it is
     * private, and is read by reflection.
     */
    @SuppressWarnings("unused")
    private final boolean parentFirst;

    /**
     * Whether this classloader is isolated, which means that a parent that is searched after this classloader's
     * classpath is not searched at all. Like Ant's, it is private, and is read by reflection.
     */
    @SuppressWarnings("unused")
    private boolean ignoreBase;

    /** The classpath of this classloader. */
    private final List<File> pathComponents = new ArrayList<>();

    /**
     * Constructor.
     *
     * @param parent
     *            the parent. The real class uses its own classloader if this is null.
     * @param parentFirst
     *            whether the parent is searched before this classloader's classpath, rather than after it.
     */
    public AntClassLoader(final ClassLoader parent, final boolean parentFirst) {
        this.parent = parent;
        this.parentFirst = parentFirst;
    }

    /**
     * Set whether this classloader is isolated.
     *
     * @param isolated
     *            whether a parent that is searched after this classloader's classpath is not searched at all.
     */
    public void setIsolated(final boolean isolated) {
        ignoreBase = isolated;
    }

    /**
     * Add an element to the classpath.
     *
     * @param pathElement
     *            the path of a jar or a directory.
     */
    public void addPathElement(final String pathElement) {
        pathComponents.add(new File(pathElement));
    }

    /**
     * Get the classpath.
     *
     * @return the absolute paths of the classpath elements, separated by the path separator.
     */
    public String getClasspath() {
        return pathComponents.stream().map(File::getAbsolutePath).collect(Collectors.joining(File.pathSeparator));
    }
}
