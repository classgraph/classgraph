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

/** Finds the version number of a Maven artifact, the operating system type, and the major version of the JDK. */
public final class VersionFinder {

    /** The operating system type. */
    public static final OperatingSystem OS;

    /** Java major version (e.g. 17 for "17.0.4", 21 for "21-ea"). */
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
        // N.B. getProperty() returns null, not the default value, if a SecurityException is thrown, so the result
        // has to be null-checked before it is lowercased -- otherwise this static initializer can throw
        // ExceptionInInitializerError, rather than falling through to OperatingSystem.Unknown as intended.
        final var osNameRaw = getProperty("os.name", "unknown");
        final var osName = osNameRaw == null ? null : osNameRaw.toLowerCase(Locale.ENGLISH);
        if (File.separatorChar == '\\') {
            OS = OperatingSystem.Windows;
        } else if (osName == null) {
            OS = OperatingSystem.Unknown;
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
     * Get a system property (returning null if a SecurityException was thrown).
     *
     * @param propName
     *            the property name
     * @return the property value
     */
    public static @Nullable String getProperty(final String propName) {
        try {
            return System.getProperty(propName);
        } catch (final SecurityException e) {
            return null;
        }
    }

    /**
     * Get a system property (returning null if a SecurityException was thrown).
     *
     * @param propName
     *            the property name
     * @param defaultVal
     *            the default value for the property
     * @return the property value, or the default if the property is not defined.
     */
    public static @Nullable String getProperty(final String propName, final String defaultVal) {
        try {
            return System.getProperty(propName, defaultVal);
        } catch (final SecurityException e) {
            return null;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the version number of the Maven artifact containing {@code classInArtifact} from
     * {@code META-INF/maven/<groupId>/<artifactId>/version.properties}, {@code pom.properties}, or the package
     * manifest.
     *
     * @param classInArtifact
     *            a class packaged in the artifact.
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
     * Trim a version string, returning {@code null} if {@code null} or blank.
     *
     * @param version
     *            the version string, or {@code null}.
     * @return the trimmed version string, or {@code null} if blank.
     */
    private static @Nullable String trimVersion(final @Nullable String version) {
        if (version == null) {
            return null;
        }
        final var versionTrimmed = version.trim();
        return versionTrimmed.isEmpty() ? null : versionTrimmed;
    }

    /**
     * Read the {@code version} property from a file under {@code META-INF/maven/<groupId>/<artifactId>/}, or return
     * {@code null} if absent, blank, or unexpanded.
     *
     * @param cls
     *            a class packaged in the artifact.
     * @param mavenGroupId
     *            the {@code groupId} of the artifact.
     * @param mavenArtifactId
     *            the {@code artifactId} of the artifact.
     * @param fileName
     *            the name of the properties file.
     * @return the version string, or {@code null} if unavailable.
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
        } catch (final IOException e) {
            // Ignore -- the properties file is absent or unreadable
        }
        return null;
    }

    /**
     * Read the implementation or specification version from the {@link Package} of {@code cls}, or return
     * {@code null} if unavailable.
     *
     * @param cls
     *            a class packaged in the artifact.
     * @return the version number, or {@code null} if unavailable.
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
