package io.github.fastclasspathscanner.issues.issue148;

import io.github.fastclasspathscanner.issues.issue148.O1.SI;

public class O2 {
    SI x = new SI() {
    };

    public O2() {
        new SI() {
        };
    }
}
