package io.github.classgraph.viz.switchmap;

import java.util.concurrent.TimeUnit;

/**
 * A class with a switch on an enum of another class, for which javac generates a synthetic class, named
 * {@code SwitchOnEnum$1}, that maps the constants of the enum to the cases of the switch. It is a top-level class
 * in a package of its own, so that a scan of the package finds the synthetic class without knowing its name.
 */
public class SwitchOnEnum {
    /**
     * Abbreviate a time unit.
     *
     * @param unit
     *            the time unit.
     * @return the abbreviation.
     */
    public String abbreviate(final TimeUnit unit) {
        switch (unit) {
        case SECONDS:
            return "s";
        case MILLISECONDS:
            return "ms";
        default:
            return unit.name();
        }
    }
}
