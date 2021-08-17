package nonapi.io.github.classgraph.utils;

/** Assertions. */
public final class Assert {
    /**
     * Throw {@link IllegalArgumentException} if the class is not an annotation.
     * 
     * @param clazz
     *            the class.
     * @throws IllegalArgumentException
     *             if the class is not an annotation.
     */
    public static void isAnnotation(final Class<?> clazz) {
        if (!clazz.isAnnotation()) {
            throw new IllegalArgumentException(clazz + " is not an annotation");
        }
    }

    /**
     * Throw {@link IllegalArgumentException} if the class is not an interface.
     * 
     * @param clazz
     *            the class.
     * @throws IllegalArgumentException
     *             if the class is not an interface.
     */
    public static void isInterface(final Class<?> clazz) {
        if (!clazz.isInterface()) {
            throw new IllegalArgumentException(clazz + " is not an interface");
        }
    }
}
