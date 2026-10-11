package io.github.classgraph.features.innerclasses;

import java.util.AbstractList;
import java.util.function.Supplier;

/**
 * A class whose type parameter bound, superclass and superinterface have nested classes of {@link NestedTypes} as
 * type arguments, with type annotations on them.
 *
 * @param <T>
 *            a type parameter with an annotated bound
 */
public abstract class AnnotatedNestedClassTypes<T extends NestedTypes.@TypeUse Inner>
        extends AbstractList<NestedTypes.@TypeUse Inner> implements Supplier<NestedTypes.Inner2.@TypeUse Deep> {
}
