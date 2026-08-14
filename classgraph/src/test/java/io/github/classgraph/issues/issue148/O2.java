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
    // The allocation is the fixture: it is what makes the compiler emit a second anonymous class
    @SuppressWarnings("unused")
    public O2() {
        new SI() {
        };
    }
}
