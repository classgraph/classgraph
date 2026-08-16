package io.github.classgraph;

import io.github.classgraph.vfs.internal.VfsSpec;

/**
 * Gives tests in other packages access to the {@link VfsSpec} of a {@link ClassGraph}, so that they can set the
 * fields that exist only so that a test can override what the platform would otherwise choose, such as
 * {@code VfsSpec#memoryMapFiles}.
 */
public final class VfsSpecAccess {
    /** Constructor. */
    private VfsSpecAccess() {
        // Cannot be constructed
    }

    /**
     * Get the archive reading settings of a {@link ClassGraph}.
     *
     * @param classGraph
     *            the {@link ClassGraph}.
     * @return its {@link VfsSpec}.
     */
    public static VfsSpec vfsSpecOf(final ClassGraph classGraph) {
        return classGraph.scanSpec.vfsSpec;
    }
}
