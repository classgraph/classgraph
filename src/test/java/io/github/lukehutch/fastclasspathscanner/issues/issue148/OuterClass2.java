package io.github.lukehutch.fastclasspathscanner.issues.issue148;

import io.github.lukehutch.fastclasspathscanner.issues.issue148.OuterClass1.StaticInnerClass;

public class OuterClass2 {
    StaticInnerClass x = new StaticInnerClass() {
    };

    public OuterClass2() {
        new StaticInnerClass() {
        };
    }
}
