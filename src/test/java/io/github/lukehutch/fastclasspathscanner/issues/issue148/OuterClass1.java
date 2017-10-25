package io.github.lukehutch.fastclasspathscanner.issues.issue148;

public class OuterClass1 {

    static class StaticInnerClass {
    }

    public class NonStaticInnerClass {
        public class NonStaticNestedInnerClass {
            public NonStaticNestedInnerClass() {
            }

            public StaticInnerClass newAnonymousStaticInnerClass() {
                return new StaticInnerClass() {
                };
            }

            public NonStaticInnerClass newAnonymousNonStaticInnerClass() {
                return new NonStaticInnerClass() {
                };
            }
        }
    }
}
