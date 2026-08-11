package io.github.classgraph;

import nonapi.io.github.classgraph.vfsspec.VfsScanSpec;

/**
 * Gives tests in other packages access to the {@link VfsScanSpec} of a {@link ClassGraph}, so that they can set the
 * fields that exist only so that a test can override what the platform would otherwise choose, such as
 * {@code VfsScanSpec#memoryMapFiles}.
 */
public final class VfsScanSpecAccess {
    /** Constructor. */
    private VfsScanSpecAccess() {
        // Cannot be constructed
    }

    /**
     * Get the archive reading settings of a {@link ClassGraph}.
     *
     * @param classGraph
     *            the {@link ClassGraph}.
     * @return its {@link VfsScanSpec}.
     */
    public static VfsScanSpec vfsScanSpecOf(final ClassGraph classGraph) {
        return classGraph.vfsScanSpec;
    }
}
