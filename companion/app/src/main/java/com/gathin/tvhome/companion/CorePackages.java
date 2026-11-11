package com.gathin.tvhome.companion;

import java.util.regex.Pattern;

/**
 * The packages Android itself needs. This is the same list as {@code CORE_RE} / {@code CORE_OK} in
 * {@code tools/disable.sh} and {@code tools/recovery.py}; keep the three in step. Without these a TV can
 * fail to boot (#884), so the companion refuses them in the UI and again before every command.
 */
public final class CorePackages {

    private static final Pattern CORE = Pattern.compile(
            "^(android"
                    + "|com\\.android\\.systemui"
                    + "|com\\.android\\.shell"
                    + "|com\\.android\\.phone"
                    + "|com\\.android\\.settings"
                    + "|com\\.android\\.tv\\.settings"
                    + "|com\\.(google\\.)?android\\.(permissioncontroller|packageinstaller|ext\\.services"
                    + "|ext\\.shared|modulemetadata)"
                    + "|com\\.google\\.android\\.overlay\\.modules.*"
                    + "|com\\.android\\.providers\\..*)$");

    /** Content providers that tested lists turn off and no TV feature uses. */
    private static final Pattern ALLOWED = Pattern.compile(
            "^com\\.android\\.providers\\.(calendar|contacts|userdictionary|blockednumber)$");

    private CorePackages() {
    }

    public static boolean isCore(String pkg) {
        return pkg != null && CORE.matcher(pkg).matches() && !ALLOWED.matcher(pkg).matches();
    }
}
