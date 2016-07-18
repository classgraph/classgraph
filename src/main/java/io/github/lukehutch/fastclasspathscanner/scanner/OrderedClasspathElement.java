package io.github.lukehutch.fastclasspathscanner.scanner;

import java.io.File;
import java.io.IOException;

import io.github.lukehutch.fastclasspathscanner.utils.FastPathResolver;

/** A class to sort classpath elements by classpath entry index, then parent path, then relative path. */
class OrderedClasspathElement implements Comparable<OrderedClasspathElement> {
    public final String orderKey;
    public final String parentPath;
    public final String relativePath;
    private String path;
    private boolean resolvedPath = false;
    private File file;
    private String canonicalPath;

    public OrderedClasspathElement(final String orderKey, final String parentURI, final String relativePath) {
        this.orderKey = orderKey;
        this.parentPath = parentURI;
        this.relativePath = relativePath;
    }

    public String getResolvedPath() {
        if (!resolvedPath) {
            path = FastPathResolver.resolve(parentPath, relativePath);
            resolvedPath = true;
        }
        return path;
    }

    public File getFile() {
        if (file == null) {
            final String path = getResolvedPath();
            if (path == null) {
                throw new RuntimeException(
                        "Path " + relativePath + " could not be resolved relative to " + parentPath);
            }
            file = new File(path);
        }
        return file;
    }

    public String getCanonicalPath() throws IOException {
        if (canonicalPath == null) {
            final File file = getFile();
            canonicalPath = file.getCanonicalPath();
        }
        return canonicalPath;
    }

    /** Sort in order of orderKey, then parentURI, then relativePath. */
    @Override
    public int compareTo(final OrderedClasspathElement o) {
        int diff = orderKey.compareTo(o.orderKey);
        if (diff == 0) {
            diff = parentPath.compareTo(o.parentPath);
        }
        if (diff == 0) {
            diff = relativePath.compareTo(o.relativePath);
        }
        return diff;
    }

    @Override
    public boolean equals(final Object o) {
        if (o == null || !(o instanceof OrderedClasspathElement)) {
            return false;
        }
        final OrderedClasspathElement other = (OrderedClasspathElement) o;
        return orderKey.equals(other.orderKey) && parentPath.equals(other.parentPath)
                && relativePath.equals(other.relativePath);
    }

    @Override
    public int hashCode() {
        return orderKey.hashCode() + parentPath.hashCode() * 7 + relativePath.hashCode() * 17;
    }

    @Override
    public String toString() {
        return orderKey + "!" + parentPath + "!" + relativePath;
    }
}