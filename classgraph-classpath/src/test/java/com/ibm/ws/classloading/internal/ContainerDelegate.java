package com.ibm.ws.classloading.internal;

import java.io.File;

import org.jspecify.annotations.Nullable;

/**
 * Stand-in for the object that a WebSphere Liberty {@code Container} delegates to, which knows either the directory
 * path it serves resources from, or the archive file it serves them from.
 */
public class ContainerDelegate {
    /** The directory path that resources are served from, or null if resources come from an archive. */
    public final @Nullable String path;

    /** The object holding the archive file that resources are served from, or null. */
    public final @Nullable Object base;

    /**
     * Constructor.
     *
     * @param path
     *            the directory path that resources are served from, or null if resources come from an archive.
     * @param base
     *            the object holding the archive file that resources are served from, or null.
     */
    public ContainerDelegate(final @Nullable String path, final @Nullable Object base) {
        this.path = path;
        this.base = base;
    }

    /** The base of a {@link ContainerDelegate} that serves resources from an archive file. */
    public static class ArchiveBase {
        /** The archive file that resources are served from. */
        public final File archiveFile;

        /**
         * Constructor.
         *
         * @param archiveFile
         *            the archive file that resources are served from.
         */
        public ArchiveBase(final File archiveFile) {
            this.archiveFile = archiveFile;
        }
    }
}
