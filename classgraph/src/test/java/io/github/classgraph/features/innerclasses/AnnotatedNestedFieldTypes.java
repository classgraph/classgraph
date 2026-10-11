package io.github.classgraph.features.innerclasses;

import java.util.List;

/**
 * A class whose fields have nested classes of {@link NestedTypes} as their types, with type annotations on them.
 * The nested class that a type annotation applies to depends on which of the nested classes are inner classes (JLS
 * 9.7.4).
 */
public class AnnotatedNestedFieldTypes {
    /** An annotated inner class. */
    public NestedTypes.@TypeUse Inner annotatedInner;

    /** An inner class with an annotated enclosing class. */
    public @TypeUse NestedTypes.Inner annotatedOuter;

    /** An annotated static nested class. */
    public NestedTypes.@TypeUse StaticNested annotatedStaticNested;

    /** An annotated inner class of an inner class. */
    public NestedTypes.Inner2.@TypeUse Deep annotatedDeepInInner;

    /** An inner class of an annotated inner class. */
    public NestedTypes.@TypeUse Inner2.Deep deepInAnnotatedInner;

    /** An annotated inner class of a static nested class. */
    public NestedTypes.StaticNested2.@TypeUse Deep annotatedDeepInStaticNested;

    /** A list of an annotated inner class. */
    public List<NestedTypes.@TypeUse Inner> listOfAnnotatedInner;
}
