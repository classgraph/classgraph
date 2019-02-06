package io.github.classgraph.issues.issue148;

import io.github.classgraph.issues.issue148.O1.SI;

/**
 * The Class O2.
 */
public class O2 {

    /** The x. */
    SI x = new SI() {
    };

    /**
     * Instantiates a new o2.
     */
    public O2() {
        new SI() {
        };
    }
}
