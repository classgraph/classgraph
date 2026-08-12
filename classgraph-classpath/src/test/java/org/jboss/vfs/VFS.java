package org.jboss.vfs;

import org.jspecify.annotations.Nullable;

/**
 * Stand-in for JBoss' {@code VFS}, the entry point to the JBoss virtual filesystem. The handler looks this class up
 * by name, so it must be in this exact package with this exact name.
 */
public class VFS {
    /** Constructor. */
    private VFS() {
    }

    /**
     * The mount that a virtual file belongs to.
     *
     * @param virtualFile
     *            the virtual file.
     * @return the mount, or null if the virtual file does not belong to one.
     */
    public static @Nullable Mount getMount(final VirtualFile virtualFile) {
        return virtualFile.mount;
    }

    /** Stand-in for JBoss' {@code VFS$Mount}, one archive mounted into the virtual filesystem. */
    public static class Mount {
        /** The filesystem that this mount serves. */
        private final VirtualFile.FileSystem fileSystem;

        /**
         * Constructor.
         *
         * @param fileSystem
         *            the filesystem that this mount serves.
         */
        Mount(final VirtualFile.FileSystem fileSystem) {
            this.fileSystem = fileSystem;
        }

        /**
         * The filesystem that this mount serves.
         *
         * @return the filesystem.
         */
        public VirtualFile.FileSystem getFileSystem() {
            return fileSystem;
        }
    }
}
