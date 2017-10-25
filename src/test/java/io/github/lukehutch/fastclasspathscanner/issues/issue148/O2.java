package io.github.lukehutch.fastclasspathscanner.issues.issue148;

import io.github.lukehutch.fastclasspathscanner.issues.issue148.O1.SI;

public class O2 {
    SI x = new SI() {
    };

    public O2() {
        new SI() {
        };
    }
}
