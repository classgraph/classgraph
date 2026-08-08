package io.github.classgraph.issues.issue940.schema;

/**
 * A class with no package segment where the {@code "**"} wildcard of
 * {@code "issue940.**.schema"} would match -- {@code "**"} matches zero or more
 * segments, so this class is matched by that glob too.
 */
public class TopLevelSchemaThing {
}
