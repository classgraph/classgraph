package io.github.classgraph.issues.issue926;

import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/** TYPE_USE annotation used on a generic type argument / parameter. */
@Target(TYPE_USE)
@Retention(RUNTIME)
@interface BridgeAnno {
}

/** Generic interface whose erased bridge method causes javac to emit an incorrect type-annotation path. */
interface BridgeInterface<C> {
    /** Method whose parameter type is erased in the bridge. */
    void f(C c);
}

/**
 * Implementation that triggers JDK-8385663: javac copies the {@code [ARRAY]} type-annotation path from the real
 * method {@code f(String[])} onto the synthetic bridge method {@code f(Object)}, where it is invalid. Used as a
 * checked-in test fixture. Recompile with a recent javac: {@code javac BridgeImpl.java}
 */
// #926, #897
public class BridgeImpl implements BridgeInterface<@BridgeAnno String[]> {
    @Override
    public void f(@BridgeAnno String[] c) {
    }
}
