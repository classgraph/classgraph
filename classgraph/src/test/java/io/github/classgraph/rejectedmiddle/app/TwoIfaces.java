package io.github.classgraph.rejectedmiddle.app;

import io.github.classgraph.rejectedmiddle.api.Other;
import io.github.classgraph.rejectedmiddle.api.TA;
import io.github.classgraph.rejectedmiddle.api.TB;
import io.github.classgraph.rejectedmiddle.gen.GenIface;

/**
 * An accepted class whose first interface is in the rejected package, with a type annotation on each interface.
 */
public class TwoIfaces implements @TA GenIface, @TB Other {
}
