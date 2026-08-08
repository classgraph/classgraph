package io.github.classgraph.issues.issue940.schema;

/**
 * A class with no package segment where the {@code "**"} wildcard of {@code "issue940.**.schema"} would match --
 * {@code "**"} matches one or more segments, so this class must not be matched by that glob.
 */
public class TopLevelSchemaThing {
}
