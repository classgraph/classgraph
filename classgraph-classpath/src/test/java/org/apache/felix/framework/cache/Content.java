package org.apache.felix.framework.cache;

import java.io.File;

import org.jspecify.annotations.Nullable;

/** Stand-in for Felix' {@code Content}, the jar or directory that a bundle revision's contents are read from. */
public class Content {
    /** The jar or directory that the contents are read from, or null if they are not backed by a file. */
    private final @Nullable File file;

    /**
     * Constructor.
     *
     * @param file
     *            the jar or directory that the contents are read from, or null if they are not backed by a file.
     */
    public Content(final @Nullable File file) {
        this.file = file;
    }

    /**
     * The jar or directory that the contents are read from.
     *
     * @return the file, or null if the contents are not backed by a file.
     */
    public @Nullable File getFile() {
        return file;
    }
}
