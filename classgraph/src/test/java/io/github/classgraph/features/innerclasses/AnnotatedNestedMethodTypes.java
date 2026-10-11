package io.github.classgraph.features.innerclasses;

/**
 * A class with a method whose types are nested classes of {@link NestedTypes}, with type annotations on them.
 */
public abstract class AnnotatedNestedMethodTypes {
    /**
     * A method.
     *
     * @param <U>
     *            a type parameter with an annotated bound
     * @param parameter
     *            the parameter
     * @return the result
     */
    public abstract <U extends NestedTypes.@TypeUse Inner2.Deep> NestedTypes.@TypeUse Inner method(
            NestedTypes.StaticNested2.@TypeUse Deep parameter);
}
