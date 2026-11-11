package com.gathin.tvhome.companion;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CorePackagesTest {

    @Test
    public void protectsWhatAndroidNeedsToBoot() {
        assertTrue(CorePackages.isCore("android"));
        assertTrue(CorePackages.isCore("com.android.systemui"));
        assertTrue(CorePackages.isCore("com.android.shell"));
        assertTrue(CorePackages.isCore("com.android.phone"));
        assertTrue(CorePackages.isCore("com.android.settings"));
        assertTrue(CorePackages.isCore("com.android.tv.settings"));
        assertTrue(CorePackages.isCore("com.android.permissioncontroller"));
        assertTrue(CorePackages.isCore("com.google.android.permissioncontroller"));
        assertTrue(CorePackages.isCore("com.android.packageinstaller"));
        assertTrue(CorePackages.isCore("com.google.android.packageinstaller"));
        assertTrue(CorePackages.isCore("com.android.ext.services"));
        assertTrue(CorePackages.isCore("com.android.ext.shared"));
        assertTrue(CorePackages.isCore("com.android.modulemetadata"));
        assertTrue(CorePackages.isCore("com.google.android.overlay.modules.permissioncontroller"));
        assertTrue(CorePackages.isCore("com.android.providers.telephony"));
    }

    @Test
    public void allowsTheFourProvidersTheToolsAllow() {
        assertFalse(CorePackages.isCore("com.android.providers.calendar"));
        assertFalse(CorePackages.isCore("com.android.providers.contacts"));
        assertFalse(CorePackages.isCore("com.android.providers.userdictionary"));
        assertFalse(CorePackages.isCore("com.android.providers.blockednumber"));
    }

    @Test
    public void leavesOrdinaryAppsAlone() {
        assertFalse(CorePackages.isCore("com.example.bloat"));
        assertFalse(CorePackages.isCore("com.netflix.ninja"));
        assertTrue(CorePackages.isCore("com.android.providers.media"));
        assertFalse(CorePackages.isCore("android.autoinstalls.config"));
        assertFalse(CorePackages.isCore(null));
        assertFalse(CorePackages.isCore(""));
    }
}
