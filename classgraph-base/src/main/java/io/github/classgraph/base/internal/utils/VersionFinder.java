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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import javax.xml.xpath.XPathFactoryConfigurationException;

import org.jspecify.annotations.Nullable;

/** Finds the version number of a Maven artifact, and the version of the JDK. */
public final class VersionFinder {

    /** XPath for the version element of a {@code pom.xml}. */
    private static final String POM_VERSION_XPATH = "/*[local-name()='project']/*[local-name()='version']";

    /** XPath for the version of the parent of a {@code pom.xml}. */
    private static final String POM_PARENT_VERSION_XPATH = "/*[local-name()='project']/*[local-name()='parent']"
            + "/*[local-name()='version']";

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
        // N.B. getProperty() returns null, not the default value, if a SecurityException is thrown, so the result
        // has to be null-checked before it is lowercased -- otherwise this static initializer can throw
        // ExceptionInInitializerError, rather than falling through to OperatingSystem.Unknown as intended.
        final var osNameRaw = getProperty("os.name", "unknown");
        final var osName = osNameRaw == null ? null : osNameRaw.toLowerCase(Locale.ENGLISH);
        if (File.separatorChar == '\\') {
            OS = OperatingSystem.Windows;
        } else if (osName == null) {
            OS = OperatingSystem.Unknown;
        } else if (osName.contains("win")) {
            OS = OperatingSystem.Windows;
        } else if (osName.contains("mac") || osName.contains("darwin")) {
            OS = OperatingSystem.MacOSX;
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

    /**
     * Constructor.
     */
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
     * Get the version number of the Maven artifact that a given class is packaged in.
     *
     * @param classInArtifact
     *            a class that is packaged in the artifact, used to find the artifact's {@code pom.xml},
     *            {@code pom.properties} or manifest.
     * @param mavenGroupId
     *            the {@code groupId} of the artifact.
     * @param mavenArtifactId
     *            the {@code artifactId} of the artifact.
     * @return the version number of the artifact, or {@code "unknown"} if it could not be determined.
     */
    public static String getVersion(final Class<?> classInArtifact, final String mavenGroupId,
            final String mavenArtifactId) {
        // Each source is tried in turn, from the most specific to the most general
        var version = versionFromPomXml(classInArtifact);
        if (version == null) {
            version = versionFromMavenProperties(classInArtifact, mavenGroupId, mavenArtifactId);
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
     * Get the version number from the {@code pom.xml} of the project that the artifact was built from. This is only
     * available when running from a build directory rather than a jar, e.g. in Eclipse.
     *
     * @param cls
     *            a class packaged in the artifact.
     * @return the version number, or null if it could not be read.
     */
    private static @Nullable String versionFromPomXml(final Class<?> cls) {
        try {
            final var className = cls.getName();
            final var classpathResource = cls.getResource("/" + className.replace('.', '/') + ".class");
            if (classpathResource == null) {
                return null;
            }
            // Remove the package segments from the path of the package directory, to get the package root
            var path = Path.of(classpathResource.toURI()).getParent();
            final var packagePathSegments = className.length() - className.replace(".", "").length();
            for (var i = 0; i < packagePathSegments && path != null; i++) {
                path = path.getParent();
            }
            // Look for the pom.xml in the package root, and up to two levels above it ("bin" or "target/classes")
            for (var i = 0; i < 3 && path != null; i++, path = path.getParent()) {
                final var version = versionFromPomXml(path.resolve("pom.xml"));
                if (version != null) {
                    return version;
                }
            }
        } catch (final Exception e) {
            // Ignore -- the classfile is not in a directory, or its URI is not a valid path
        }
        return null;
    }

    /**
     * Read the {@code /project/version} element of a {@code pom.xml} file, falling back to
     * {@code /project/parent/version} if there is no version element -- a module of a multi-module build usually
     * omits its own version, and inherits the parent's.
     *
     * @param pom
     *            the path of the {@code pom.xml} file.
     * @return the version number, or null if the file does not exist or has no non-blank version element.
     * @throws Exception
     *             if the file exists but could not be parsed, or the XML parser or XPath factory could not be
     *             configured. The caller abandons the {@code pom.xml} search in that case, rather than trying the
     *             enclosing directories.
     */
    private static @Nullable String versionFromPomXml(final Path pom) throws Exception {
        try (var inputStream = Files.newInputStream(pom)) {
            final var doc = getSecureDocumentBuilderFactory().newDocumentBuilder().parse(inputStream);
            doc.getDocumentElement().normalize();
            final var xPath = getSecureXPathFactory().newXPath();
            // The document is parsed namespace-aware, and a pom.xml is in the Maven POM namespace, so the element
            // names have to be matched with local-name() -- an unprefixed "/project/version" matches nothing
            final var version = trimVersion(
                    (String) xPath.compile(POM_VERSION_XPATH).evaluate(doc, XPathConstants.STRING));
            return version != null ? version
                    : trimVersion(
                            (String) xPath.compile(POM_PARENT_VERSION_XPATH).evaluate(doc, XPathConstants.STRING));
        } catch (final IOException e) {
            // There is no pom.xml in this directory
            return null;
        }
    }

    /**
     * Get the version number from the Maven {@code pom.properties} in the jar's {@code META-INF} directory.
     *
     * @param cls
     *            a class packaged in the artifact.
     * @param mavenGroupId
     *            the {@code groupId} of the artifact.
     * @param mavenArtifactId
     *            the {@code artifactId} of the artifact.
     * @return the version number, or null if it could not be read.
     */
    private static @Nullable String versionFromMavenProperties(final Class<?> cls, final String mavenGroupId,
            final String mavenArtifactId) {
        try (var inputStream = cls.getResourceAsStream(
                "/META-INF/maven/" + mavenGroupId + "/" + mavenArtifactId + "/pom.properties")) {
            if (inputStream != null) {
                final var properties = new Properties();
                properties.load(inputStream);
                return trimVersion(properties.getProperty("version"));
            }
        } catch (final IOException e) {
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

    /**
     * Helper method to provide a XXE secured DocumentBuilder Factory.
     *
     * reference - https://gist.github.com/AlainODea/1779a7c6a26a5c135280bc9b3b71868f
     *
     * reference - https://rules.sonarsource.com/java/tag/owasp/RSPEC-2755
     *
     * @return DocumentBuilderFactory
     * @throws ParserConfigurationException
     *             if a requested feature is not supported by the XML parser
     */
    private static DocumentBuilderFactory getSecureDocumentBuilderFactory() throws ParserConfigurationException {
        final var dbf = DocumentBuilderFactory.newInstance();
        dbf.setXIncludeAware(false);
        dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        dbf.setExpandEntityReferences(false);
        dbf.setNamespaceAware(true);
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        return dbf;
    }

    /**
     * Helper method to provide a XXE secured XPathFactory Factory.
     *
     * reference - https://rules.sonarsource.com/java/tag/owasp/RSPEC-2755
     *
     * @return XPathFactory
     * @throws XPathFactoryConfigurationException
     *             if secure processing could not be enabled
     */
    private static XPathFactory getSecureXPathFactory() throws XPathFactoryConfigurationException {
        final var xPathFactory = XPathFactory.newInstance();
        xPathFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        return xPathFactory;
    }
}
