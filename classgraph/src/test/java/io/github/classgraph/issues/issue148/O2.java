package io.github.classgraph.issues.issue148;

import io.github.classgraph.issues.issue148.O1.SI;

/**
 * O2.
 */
public class O2 {
    /** The x. */
    SI x = new SI() {
    };

    /**
     * Constructor.
     */
    public O2() {
        new SI() {
        };
    }
}
