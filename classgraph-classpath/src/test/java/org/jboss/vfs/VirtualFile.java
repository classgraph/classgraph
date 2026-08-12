package org.jboss.vfs;

import java.io.File;

import org.jspecify.annotations.Nullable;

/**
 * Stand-in for JBoss' {@code VirtualFile}, a file in the JBoss virtual filesystem. The physical file it is backed
 * by is reached differently depending on the JBoss version, so this stand-in can be built either way.
 */
public class VirtualFile {
    /** The physical file that backs this virtual file, or null if it is not reachable that way. */
    private final @Nullable File physicalFile;

    /** The name of this virtual file, or null if it does not report one. */
    private final @Nullable String name;

    /** The mount that this virtual file belongs to, or null if it does not report one. */
    VFS.@Nullable Mount mount;

    /**
     * Constructor.
     *
     * @param physicalFile
     *            the physical file that backs this virtual file, or null if it is not reachable that way.
     * @param name
     *            the name of this virtual file, or null if it does not report one.
     */
    public VirtualFile(final @Nullable File physicalFile, final @Nullable String name) {
        this.physicalFile = physicalFile;
        this.name = name;
    }

    /**
     * Mount this virtual file from an archive on disk, the way newer JBoss versions report the physical file.
     *
     * @param mountSource
     *            the archive on disk that this virtual file is mounted from.
     * @return this, for chaining.
     */
    public VirtualFile mountedFrom(final File mountSource) {
        this.mount = new VFS.Mount(new FileSystem(mountSource));
        return this;
    }

    /**
     * The physical file that backs this virtual file.
     *
     * @return the physical file, or null if it is not reachable that way.
     */
    public @Nullable File getPhysicalFile() {
        return physicalFile;
    }

    /**
     * The name of this virtual file.
     *
     * @return the name, or null if it does not report one.
     */
    public @Nullable String getName() {
        return name;
    }

    /**
     * The path of this virtual file within the virtual filesystem.
     *
     * @return the path, or null if it does not report one.
     */
    public @Nullable String getPathName() {
        return null;
    }

    /** Stand-in for the JBoss VFS {@code FileSystem}, which knows the archive a mount was created from. */
    public static class FileSystem {
        /** The archive on disk that the mount was created from. */
        private final File mountSource;

        /**
         * Constructor.
         *
         * @param mountSource
         *            the archive on disk that the mount was created from.
         */
        FileSystem(final File mountSource) {
            this.mountSource = mountSource;
        }

        /**
         * The archive on disk that the mount was created from.
         *
         * @return the archive.
         */
        public File getMountSource() {
            return mountSource;
        }
    }
}
