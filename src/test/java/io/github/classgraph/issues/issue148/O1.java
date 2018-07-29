package io.github.classgraph.issues.issue148;

public class O1 {

    static class SI {
    }

    public class I {
        public class II {
            public II() {
            }

            public SI newSI() {
                return new SI() {
                };
            }

            public I newI() {
                return new I() {
                };
            }
        }
    }
}
