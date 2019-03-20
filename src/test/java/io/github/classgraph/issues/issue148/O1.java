package io.github.classgraph.issues.issue148;

/**
 * O1.
 */
public class O1 {
    /**
     * The Class SI.
     */
    static class SI {
    }

    /**
     * The Class I.
     */
    public class I {
        /**
         * The Class II.
         */
        public class II {
            /**
             * Constructor.
             */
            public II() {
            }

            /**
             * New SI.
             *
             * @return the si
             */
            public SI newSI() {
                return new SI() {
                };
            }

            /**
             * New I.
             *
             * @return the i
             */
            public I newI() {
                return new I() {
                };
            }
        }
    }
}
