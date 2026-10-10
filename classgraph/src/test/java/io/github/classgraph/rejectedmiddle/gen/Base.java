package io.github.classgraph.rejectedmiddle.gen;

import io.github.classgraph.rejectedmiddle.api.Component;
import io.github.classgraph.rejectedmiddle.api.OnStart;
import io.github.classgraph.rejectedmiddle.api.Service;

/**
 * A superclass in the rejected package, between an accepted class and its interface and annotations.
 */
@Component
public abstract class Base implements Service {
    /**
     * A public annotated method, which subclasses inherit.
     */
    @OnStart
    public void init() {
    }
}
