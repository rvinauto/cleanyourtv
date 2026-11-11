package com.gathin.tvhome.companion;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every shell command the app sends, built in one place so the JVM tests can check it without a TV.
 * Package names are validated before they are concatenated: a package name is only letters, digits,
 * dots and underscores, so no shell metacharacter can reach {@code pm} or {@code am}.
 */
public final class Commands {

    /** Fixed remote paths: the app never builds a path from user input. */
    public static final String REMOTE_APK = "/data/local/tmp/cleanyourtv-companion.apk";
    public static final String REMOTE_SHOT = "/data/local/tmp/cleanyourtv-companion.png";

    private static final Pattern PACKAGE = Pattern.compile("^[A-Za-z0-9._]+$");
    private static final Pattern COMPONENT =
            Pattern.compile("^[A-Za-z0-9._]+/[A-Za-z0-9._$]+$");
    private static final Pattern CATEGORY = Pattern.compile("^[A-Za-z0-9._]+$");

    private Commands() {
    }

    /** One package per action: a name with a space, semicolon or newline is rejected, not quoted away. */
    public static boolean isValidPackage(String pkg) {
        if (pkg == null || pkg.isEmpty() || pkg.length() > 255) return false;
        if (!PACKAGE.matcher(pkg).matches()) return false;
        return !pkg.startsWith(".") && !pkg.endsWith(".") && !pkg.contains("..");
    }

    public static void requirePackage(String pkg) {
        if (!isValidPackage(pkg)) {
            throw new IllegalArgumentException("not a package name: " + pkg);
        }
    }

    public static String listThirdParty() {
        return "pm list packages -3";
    }

    public static String listSystem() {
        return "pm list packages -s";
    }

    public static String listDisabled() {
        return "pm list packages -d";
    }

    public static String disable(String pkg) {
        requirePackage(pkg);
        if (CorePackages.isCore(pkg)) {
            throw new IllegalArgumentException("refusing core package: " + pkg);
        }
        return "pm disable-user --user 0 " + pkg;
    }

    public static String enable(String pkg) {
        requirePackage(pkg);
        return "pm enable " + pkg;
    }

    public static String forceStop(String pkg) {
        requirePackage(pkg);
        return "am force-stop " + pkg;
    }

    /** Ask the TV which activity would answer a remote/leanback launch for this package. */
    public static String resolveLauncher(String pkg) {
        return resolveActivity(pkg, "android.intent.category.LEANBACK_LAUNCHER");
    }

    public static String resolvePhoneLauncher(String pkg) {
        return resolveActivity(pkg, "android.intent.category.LAUNCHER");
    }

    private static String resolveActivity(String pkg, String category) {
        requirePackage(pkg);
        if (!CATEGORY.matcher(category).matches()) {
            throw new IllegalArgumentException("not a category: " + category);
        }
        return "cmd package resolve-activity --brief -a android.intent.action.MAIN -c "
                + category + " " + pkg;
    }

    public static String startComponent(String component) {
        if (component == null || !COMPONENT.matcher(component).matches()) {
            throw new IllegalArgumentException("not a component: " + component);
        }
        return "am start -n " + component;
    }

    public static String install(String remotePath) {
        return "pm install -r " + shellQuote(remotePath);
    }

    public static String screencap(String remotePath) {
        return "screencap -p " + shellQuote(remotePath);
    }

    public static String remove(String remotePath) {
        return "rm -f " + shellQuote(remotePath);
    }

    public static String quoteForShell(String value) {
        return shellQuote(value);
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /** {@code pm list packages} output to a sorted set of names. */
    public static Set<String> parsePackages(String output) {
        Set<String> names = new LinkedHashSet<>();
        if (output == null) return names;
        for (String raw : output.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.startsWith("package:")) line = line.substring("package:".length()).trim();
            if (isValidPackage(line)) names.add(line);
        }
        return names;
    }

    /** A pasted list: one package per line, {@code #} comments and blank lines skipped. */
    public static List<String> parsePackageList(String text) {
        Set<String> names = new LinkedHashSet<>();
        if (text == null) return new ArrayList<>();
        for (String raw : text.split("\\r?\\n")) {
            String line = raw;
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash);
            line = line.trim();
            if (line.isEmpty()) continue;
            // One package per line, so a pasted shell command ("pm uninstall x") is skipped whole
            // instead of contributing three nonsense names to the disable loop.
            String[] parts = line.split("[\\s,]+");
            if (parts.length != 1) continue;
            if (isValidPackage(parts[0])) names.add(parts[0]);
        }
        return new ArrayList<>(names);
    }

    /** The component from {@code cmd package resolve-activity --brief}, or null when there is none. */
    public static String parseComponent(String output) {
        if (output == null) return null;
        for (String raw : output.split("\\r?\\n")) {
            String line = raw.trim();
            Matcher m = COMPONENT.matcher(line);
            if (m.matches()) return line;
        }
        return null;
    }
}
