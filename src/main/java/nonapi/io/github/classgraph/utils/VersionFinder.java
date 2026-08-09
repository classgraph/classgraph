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
 * Copyright (c) 2019 Luke Hutchison
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
package nonapi.io.github.classgraph.utils;

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

import io.github.classgraph.ClassGraph;
import org.jspecify.annotations.Nullable;

/** Finds the version number of ClassGraph, and the version of the JDK. */
public final class VersionFinder {

    /** The Maven package for ClassGraph. */
    private static final String MAVEN_PACKAGE = "io.github.classgraph";

    /** The Maven artifact for ClassGraph. */
    private static final String MAVEN_ARTIFACT = "classgraph";

    /** The operating system type. */
    public static final OperatingSystem OS;

    /** Java version string (null if the {@code "java.version"} property is unreadable). */
    public static final @Nullable String JAVA_VERSION = getProperty("java.version");

    /** Java major version -- 17 for "17.0.4", 21 for "21-ea", etc. */
    public static final int JAVA_MAJOR_VERSION;

    /** Java minor version -- 0 for "11.0.4" */
    public static final int JAVA_MINOR_VERSION;

    /** Java sub version -- 4 for "11.0.4" */
    public static final int JAVA_SUB_VERSION;

    /** Java is EA release -- true for "11-ea", etc. */
    public static final boolean JAVA_IS_EA_VERSION;

    static {
        final var version = Runtime.version();
        JAVA_MAJOR_VERSION = version.feature();
        JAVA_MINOR_VERSION = version.interim();
        JAVA_SUB_VERSION = version.update();
        JAVA_IS_EA_VERSION = version.pre().isPresent();
    }

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
        // N.B. getProperty() returns null, not the default value, if a
        // SecurityException is thrown, so the
        // result has to be null-checked before it is lowercased -- otherwise this
        // static initializer can throw
        // ExceptionInInitializerError, rather than falling through to
        // OperatingSystem.Unknown as intended.
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
     * @param propName the property name
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
     * @param propName   the property name
     * @param defaultVal the default value for the property
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
     * Get the version number of ClassGraph.
     *
     * @return the version number of ClassGraph.
     */
    public static synchronized String getVersion() {
        // Try to get version number from pom.xml (available when running in Eclipse)
        final Class<?> cls = ClassGraph.class;
        try {
            final var className = cls.getName();
            final var classpathResource = cls.getResource("/" + JarUtils.classNameToClassfilePath(className));
            if (classpathResource != null) {
                final var absolutePackagePath = Path.of(classpathResource.toURI()).getParent();
                final var packagePathSegments = className.length() - className.replace(".", "").length();
                // Remove package segments from path
                var path = absolutePackagePath;
                for (var i = 0; i < packagePathSegments && path != null; i++) {
                    path = path.getParent();
                }
                // Remove up to two more levels for "bin" or "target/classes"
                for (var i = 0; i < 3 && path != null; i++, path = path.getParent()) {
                    final var pom = path.resolve("pom.xml");
                    try (var is = Files.newInputStream(pom)) {
                        final var doc = getSecureDocumentBuilderFactory().newDocumentBuilder().parse(is);
                        doc.getDocumentElement().normalize();
                        var version = (String) getSecureXPathFactory().newXPath().compile("/project/version")
                                .evaluate(doc, XPathConstants.STRING);
                        if (version != null) {
                            version = version.trim();
                            if (!version.isEmpty()) {
                                return version;
                            }
                        }
                    } catch (final IOException e) {
                        // Not found
                    }
                }
            }
        } catch (final Exception e) {
            // Ignore
        }

        // Try to get version number from maven properties in jar's META-INF directory
        try (var is = cls
                .getResourceAsStream("/META-INF/maven/" + MAVEN_PACKAGE + "/" + MAVEN_ARTIFACT + "/pom.properties")) {
            if (is != null) {
                final Properties p = new Properties();
                p.load(is);
                final var version = p.getProperty("version", "").trim();
                if (!version.isEmpty()) {
                    return version;
                }
            }
        } catch (final IOException e) {
            // Ignore
        }

        // Fallback to using Java API (version number is obtained from MANIFEST.MF)
        final var pkg = cls.getPackage();
        if (pkg != null) {
            var version = pkg.getImplementationVersion();
            if (version == null) {
                version = "";
            }
            version = version.trim();
            if (version.isEmpty()) {
                version = pkg.getSpecificationVersion();
                if (version == null) {
                    version = "";
                }
                version = version.trim();
            }
            if (!version.isEmpty()) {
                return version;
            }
        }
        return "unknown";
    }

    /**
     * Helper method to provide a XXE secured DocumentBuilder Factory.
     *
     * reference -
     * https://gist.github.com/AlainODea/1779a7c6a26a5c135280bc9b3b71868f
     * 
     * reference - https://rules.sonarsource.com/java/tag/owasp/RSPEC-2755
     * 
     * @return DocumentBuilderFactory
     * @throws ParserConfigurationException if a requested feature is not supported
     *                                      by the XML parser
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
     * @throws XPathFactoryConfigurationException if secure processing could not be
     *                                            enabled
     */
    private static XPathFactory getSecureXPathFactory() throws XPathFactoryConfigurationException {
        final var xPathFactory = XPathFactory.newInstance();
        xPathFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        return xPathFactory;
    }
}
