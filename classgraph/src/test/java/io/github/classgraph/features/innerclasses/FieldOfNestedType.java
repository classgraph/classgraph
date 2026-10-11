package io.github.classgraph.features.innerclasses;

import java.util.Map;

/**
 * A class with a field whose type is a nested class of another class. The compiler writes an {@code InnerClasses}
 * entry for {@code java.util.Map$Entry} into this classfile.
 */
public class FieldOfNestedType {
    /** The field. It is never read, so the classfile has no constant pool reference to its type. */
    public Map.Entry<String, String> entry;
}
