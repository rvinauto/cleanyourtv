package com.gathin.tvhome.companion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

public class CommandsTest {

    @Test
    public void acceptsRealPackageNames() {
        assertTrue(Commands.isValidPackage("com.example.app"));
        assertTrue(Commands.isValidPackage("com.example.app_2"));
        assertTrue(Commands.isValidPackage("a"));
        assertTrue(Commands.isValidPackage("com.android.providers.calendar"));
    }

    @Test
    public void rejectsShellMetacharactersAndBadShapes() {
        assertFalse(Commands.isValidPackage(null));
        assertFalse(Commands.isValidPackage(""));
        assertFalse(Commands.isValidPackage("com.example app"));
        assertFalse(Commands.isValidPackage("com.example;id"));
        assertFalse(Commands.isValidPackage("com.example\npm uninstall x"));
        assertFalse(Commands.isValidPackage("com.example.app|id"));
        assertFalse(Commands.isValidPackage("$(id)"));
        assertFalse(Commands.isValidPackage(".com.example"));
        assertFalse(Commands.isValidPackage("com.example."));
        assertFalse(Commands.isValidPackage("com..example"));
        assertFalse(Commands.isValidPackage("com/example"));
        assertFalse(Commands.isValidPackage("x".repeat(256)));
    }

    @Test
    public void disableIsOnePackageAndRefusesCore() {
        assertEquals("pm disable-user --user 0 com.example.bloat", Commands.disable("com.example.bloat"));
        try {
            Commands.disable("com.android.systemui");
            throw new AssertionError("core package was not refused");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("com.android.systemui"));
        }
    }

    @Test
    public void enableAndStopUseTheSameValidatedName() {
        assertEquals("pm enable com.example.app", Commands.enable("com.example.app"));
        assertEquals("am force-stop com.example.app", Commands.forceStop("com.example.app"));
    }

    @Test
    public void listCommandsAreFixed() {
        assertEquals("pm list packages -3", Commands.listThirdParty());
        assertEquals("pm list packages -s", Commands.listSystem());
        assertEquals("pm list packages -d", Commands.listDisabled());
    }

    @Test
    public void installQuotesTheFixedRemotePath() {
        assertEquals("pm install -r '/data/local/tmp/cleanyourtv-companion.apk'",
                Commands.install(Commands.REMOTE_APK));
    }

    @Test
    public void parsesPackageOutput() {
        Set<String> names = Commands.parsePackages(
                "package:com.example.one\r\npackage:com.example.two\n\njunk line\n");
        assertEquals(2, names.size());
        assertTrue(names.contains("com.example.one"));
        assertTrue(names.contains("com.example.two"));
    }

    @Test
    public void parsesPastedListWithCommentsAndDuplicates() {
        List<String> names = Commands.parsePackageList(
                "# keep this comment\ncom.example.one\n\ncom.example.two # trailing\ncom.example.one\n");
        assertEquals(Arrays.asList("com.example.one", "com.example.two"), names);
    }

    @Test
    public void ignoresInvalidLinesInPastedList() {
        List<String> names = Commands.parsePackageList(
                "com.example.one\npm uninstall x\ncom.example.two com.example.three\n");
        assertEquals(1, names.size());
        assertEquals("com.example.one", names.get(0));
    }

    @Test
    public void parsesResolvedComponent() {
        String output = "priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true\n"
                + "com.example.app/.MainActivity\n";
        assertEquals("com.example.app/.MainActivity", Commands.parseComponent(output));
        assertNull(Commands.parseComponent("No activity found"));
    }

    @Test
    public void componentCommandsRejectInjection() {
        assertEquals("am start -n com.example.app/.MainActivity",
                Commands.startComponent("com.example.app/.MainActivity"));
        try {
            Commands.startComponent("com.example.app/.MainActivity; id");
            throw new AssertionError("component injection was not refused");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("component"));
        }
    }
}
