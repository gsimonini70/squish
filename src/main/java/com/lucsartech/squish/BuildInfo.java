package com.lucsartech.squish;

import java.util.Properties;
import java.util.function.Supplier;

/**
 * Identity of the running build. {@code version} is the release (pom.xml {@code <version>});
 * {@code buildNumber} is the git commit the jar was built from — what tells apart the several
 * stages a single version can ship in (an RC, a hotfix rebuild, a local {@code .dirty} build).
 *
 * <p>Both are stamped into {@code build.properties} at package time by Maven resource filtering
 * ({@code @project.version@} / {@code @git.commit@} / {@code @build.time@}). {@code build-dist.sh}
 * passes {@code -Dgit.commit=<short-sha>[.dirty]} so a distributed bundle always carries its commit,
 * while a plain {@code mvn package} leaves it {@code "unknown"}.
 *
 * <p>Everything degrades gracefully and this class never throws or returns null: with no filtered
 * resource (IDE / exploded run where the placeholders survive as {@code @...@}) the version falls
 * back to the jar manifest and then to {@code "dev"}, and the commit/time to {@code "unknown"}.
 */
public final class BuildInfo {

    /** Identifies our own fat-jar manifest by its {@code Start-Class}, not by classpath order. */
    private static final String MAIN_CLASS = "com.lucsartech.squish.SquishApplication";

    private static final String VERSION;
    private static final String BUILD_NUMBER;
    private static final String BUILD_TIME;

    static {
        Properties p = load();
        VERSION = resolve(p.getProperty("build.version"), BuildInfo::versionFromManifest, "dev");
        BUILD_NUMBER = resolve(p.getProperty("build.commit"), () -> null, "unknown");
        BUILD_TIME = resolve(p.getProperty("build.time"), () -> null, "unknown");
    }

    private BuildInfo() {
    }

    /** Release version, e.g. {@code 3.2.0}; {@code "dev"} when running without a build stamp. */
    public static String version() {
        return VERSION;
    }

    /** Build number (git commit the jar was built from); {@code "unknown"} for an unstamped build. */
    public static String buildNumber() {
        return BUILD_NUMBER;
    }

    /** UTC build timestamp; {@code "unknown"} for an unstamped build. */
    public static String buildTime() {
        return BUILD_TIME;
    }

    /** Display form, e.g. {@code 3.2.0+a1b2c3d} — or just {@code 3.2.0} when the commit is unknown. */
    public static String fullVersion() {
        return "unknown".equals(BUILD_NUMBER) ? VERSION : VERSION + "+" + BUILD_NUMBER;
    }

    /**
     * A filtered property value is usable only if present, non-blank, and not a surviving
     * {@code @...@} placeholder (which means resource filtering never ran). Otherwise fall back to
     * {@code fallback}, and finally to {@code last}.
     */
    private static String resolve(String value, Supplier<String> fallback, String last) {
        if (value != null && !value.isBlank() && !(value.startsWith("@") && value.endsWith("@"))) {
            return value;
        }
        String f = fallback.get();
        return (f != null && !f.isBlank()) ? f : last;
    }

    private static Properties load() {
        var props = new Properties();
        try (var in = BuildInfo.class.getClassLoader().getResourceAsStream("build.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (Exception ignored) {
            // Build metadata is cosmetic: never let it break startup or an API call.
        }
        return props;
    }

    /** Manifest fallback for the version alone (mirrors the old SquishApplication resolution). */
    private static String versionFromManifest() {
        String v = BuildInfo.class.getPackage().getImplementationVersion();
        if (v != null && !v.isBlank()) {
            return v;
        }
        try {
            var manifests = BuildInfo.class.getClassLoader().getResources("META-INF/MANIFEST.MF");
            while (manifests.hasMoreElements()) {
                try (var in = manifests.nextElement().openStream()) {
                    var attributes = new java.util.jar.Manifest(in).getMainAttributes();
                    if (MAIN_CLASS.equals(attributes.getValue("Start-Class"))) {
                        return attributes.getValue("Implementation-Version");
                    }
                }
            }
        } catch (Exception ignored) {
            // Fall through to null; the caller supplies the "dev" default.
        }
        return null;
    }
}
