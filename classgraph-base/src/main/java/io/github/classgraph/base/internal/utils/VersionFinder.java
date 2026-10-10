/*
 * This file is part of ClassGraph.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/classgraph/classgraph
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2026 Luke Hutchison
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.github.classgraph.base.internal.utils;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Properties;

import org.jspecify.annotations.Nullable;

/** Finds the version number of a Maven artifact, and the version of the JDK. */
public final class VersionFinder {

    /** The operating system type. */
    public static final OperatingSystem OS;

    /** Java major version -- 17 for "17.0.4", 21 for "21-ea", etc. */
    public static final int JAVA_MAJOR_VERSION = Runtime.version().feature();

    /** The operating system type. */
    public enum OperatingSystem {
        /** Windows. */
        Windows,

        /** Mac OS X. */
        MacOSX,

        /** Linux. */
        Linux,

        /** Solaris. */
        Solaris,

        /** BSD. */
        BSD,

        /** Unix or AIX. */
        Unix,

        /** Unknown. */
        Unknown
    }

    static {
        final var osName = System.getProperty("os.name", "unknown").toLowerCase(Locale.ENGLISH);
        if (File.separatorChar == '\\') {
            OS = OperatingSystem.Windows;
        } else if (osName.contains("mac") || osName.contains("darwin")) {
            // Tested before "win", since "darwin" contains "win"
            OS = OperatingSystem.MacOSX;
        } else if (osName.contains("win")) {
            OS = OperatingSystem.Windows;
        } else if (osName.contains("nux")) {
            OS = OperatingSystem.Linux;
        } else if (osName.contains("sunos") || osName.contains("solaris")) {
            OS = OperatingSystem.Solaris;
        } else if (osName.contains("bsd")) {
            OS = OperatingSystem.BSD;
        } else if (osName.contains("nix") || osName.contains("aix")) {
            OS = OperatingSystem.Unix;
        } else {
            OS = OperatingSystem.Unknown;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Not instantiable. */
    private VersionFinder() {
        // Cannot be constructed
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the version number of the Maven artifact that a given class is packaged in. The version is read from the
     * first of these that records one:
     *
     * <ol>
     * <li>{@code META-INF/maven/<groupId>/<artifactId>/version.properties}, which the build of each ClassGraph
     * module generates by resource filtering, so it is present both in the jar and in a build directory such as
     * {@code target/classes};</li>
     * <li>{@code META-INF/maven/<groupId>/<artifactId>/pom.properties}, which Maven writes into every jar it
     * builds;</li>
     * <li>the implementation or specification version of the class's {@link Package}, which the JDK reads from the
     * jar manifest.</li>
     * </ol>
     *
     * <p>
     * The two properties files are under {@code META-INF/}, not in a package directory, because a resource in a
     * package of a named module can only be read by that module, and this method is called from another module.
     *
     * @param classInArtifact
     *            a class that is packaged in the artifact, used to find the artifact's properties files or
     *            manifest.
     * @param mavenGroupId
     *            the {@code groupId} of the artifact.
     * @param mavenArtifactId
     *            the {@code artifactId} of the artifact.
     * @return the version number of the artifact, or {@code "unknown"} if it could not be determined.
     */
    public static String getVersion(final Class<?> classInArtifact, final String mavenGroupId,
            final String mavenArtifactId) {
        // Each source is tried in turn, from the most specific to the most general
        var version = versionFromMavenProperties(classInArtifact, mavenGroupId, mavenArtifactId,
                "version.properties");
        if (version == null) {
            version = versionFromMavenProperties(classInArtifact, mavenGroupId, mavenArtifactId, "pom.properties");
        }
        if (version == null) {
            version = versionFromPackage(classInArtifact);
        }
        return version == null ? "unknown" : version;
    }

    /**
     * Trim a version string, mapping null and the empty string to null, so that an absent version and a blank one
     * are treated the same way.
     *
     * @param version
     *            the version string, or null.
     * @return the trimmed version string, or null if there was no non-blank version.
     */
    private static @Nullable String trimVersion(final @Nullable String version) {
        if (version == null) {
            return null;
        }
        final var versionTrimmed = version.trim();
        return versionTrimmed.isEmpty() ? null : versionTrimmed;
    }

    /**
     * Get the version number from a properties file in the artifact's {@code META-INF/maven/<groupId>/<artifactId>}
     * directory.
     *
     * @param cls
     *            a class packaged in the artifact.
     * @param mavenGroupId
     *            the {@code groupId} of the artifact.
     * @param mavenArtifactId
     *            the {@code artifactId} of the artifact.
     * @param fileName
     *            the name of the properties file.
     * @return the value of the {@code version} property, or null if the file could not be read, or has no non-blank
     *         version, or has a version that is an unexpanded Maven property reference such as
     *         {@code ${project.version}} (because the file was copied by a build that does not filter resources).
     */
    private static @Nullable String versionFromMavenProperties(final Class<?> cls, final String mavenGroupId,
            final String mavenArtifactId, final String fileName) {
        try (var inputStream = cls
                .getResourceAsStream("/META-INF/maven/" + mavenGroupId + "/" + mavenArtifactId + "/" + fileName)) {
            if (inputStream != null) {
                final var properties = new Properties();
                properties.load(inputStream);
                final var version = trimVersion(properties.getProperty("version"));
                return version == null || version.startsWith("${") ? null : version;
            }
        } catch (final IOException _) {
            // Ignore -- the properties file is absent or unreadable
        }
        return null;
    }

    /**
     * Get the version number from the {@link Package} of a class in the artifact, which the JDK reads from the
     * jar's {@code MANIFEST.MF}.
     *
     * @param cls
     *            a class packaged in the artifact.
     * @return the version number, or null if it could not be read.
     */
    private static @Nullable String versionFromPackage(final Class<?> cls) {
        final var pkg = cls.getPackage();
        if (pkg == null) {
            return null;
        }
        final var implementationVersion = trimVersion(pkg.getImplementationVersion());
        return implementationVersion != null ? implementationVersion : trimVersion(pkg.getSpecificationVersion());
    }
}
