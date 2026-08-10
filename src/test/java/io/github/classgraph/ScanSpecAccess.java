package io.github.classgraph;

import nonapi.io.github.classgraph.scanspec.ScanSpec;

/**
 * Gives tests in other packages access to the {@link ScanSpec} of a {@link ClassGraph}, so that they can set the
 * fields that exist only so that a test can override what the platform would otherwise choose, such as
 * {@code ScanSpec#memoryMapFiles}.
 */
public final class ScanSpecAccess {
    /** Constructor. */
    private ScanSpecAccess() {
        // Cannot be constructed
    }

    /**
     * Get the scan spec of a {@link ClassGraph}.
     *
     * @param classGraph
     *            the {@link ClassGraph}.
     * @return its {@link ScanSpec}.
     */
    public static ScanSpec scanSpecOf(final ClassGraph classGraph) {
        return classGraph.scanSpec;
    }
}
