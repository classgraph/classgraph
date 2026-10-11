package io.github.classgraph.features.innerclasses;

import java.util.List;
import java.util.Map;

/**
 * A class with a field whose type has a type argument that is a nested class of another class, which is named only
 * in the type signature of the field. The compiler writes an {@code InnerClasses} entry for
 * {@code java.util.Map$Entry} into this classfile.
 */
public class FieldOfListOfNestedType {
    /** The field. */
    public List<Map.Entry<String, String>> entries;
}
