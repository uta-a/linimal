package dev.utaa.linimal.extension.settings;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SettingsNavigationTest {
    @Test
    public void startsAtRootAndPushesOneChildPage() {
        SettingsNavigation navigation = new SettingsNavigation();

        assertEquals(SettingsPage.ROOT, navigation.getCurrentPage());
        assertTrue(navigation.push(SettingsPage.GENERAL));
        assertEquals(SettingsPage.GENERAL, navigation.getCurrentPage());
    }

    @Test
    public void pushRejectsRootNullDuplicateAndAdditionalDepth() {
        SettingsNavigation navigation = new SettingsNavigation();

        assertFalse(navigation.push(null));
        assertFalse(navigation.push(SettingsPage.ROOT));
        assertTrue(navigation.push(SettingsPage.HOME));
        assertFalse(navigation.push(SettingsPage.HOME));
        assertFalse(navigation.push(SettingsPage.CHAT));
        assertEquals(SettingsPage.HOME, navigation.getCurrentPage());
    }

    @Test
    public void popDistinguishesReturningToRootFromFinishingActivity() {
        SettingsNavigation navigation = new SettingsNavigation();
        navigation.push(SettingsPage.TABS);

        assertEquals(SettingsNavigation.PopResult.RETURNED_TO_ROOT, navigation.pop());
        assertEquals(SettingsPage.ROOT, navigation.getCurrentPage());
        assertEquals(SettingsNavigation.PopResult.FINISH_ACTIVITY, navigation.pop());
        assertEquals(SettingsPage.ROOT, navigation.getCurrentPage());
    }

    @Test
    public void restoreFallsBackToRootForInvalidPaths() {
        assertRestoresRoot(null);
        assertRestoresRoot(new String[]{});
        assertRestoresRoot(new String[]{"GENERAL"});
        assertRestoresRoot(new String[]{"UNKNOWN"});
        assertRestoresRoot(new String[]{"ROOT", "UNKNOWN"});
        assertRestoresRoot(new String[]{"ROOT", null});
        assertRestoresRoot(new String[]{"ROOT", "ROOT"});
        assertRestoresRoot(new String[]{"ROOT", "GENERAL", "HOME"});
    }

    @Test
    public void serializeRoundTripRestoresRootAndChildPaths() {
        SettingsNavigation rootNavigation = new SettingsNavigation();
        assertArrayEquals(new String[]{"ROOT"}, rootNavigation.serialize());

        SettingsNavigation childNavigation = new SettingsNavigation();
        childNavigation.push(SettingsPage.PATCH_STATUS);
        String[] savedPath = childNavigation.serialize();

        SettingsNavigation restoredNavigation = new SettingsNavigation();
        restoredNavigation.restore(savedPath);
        assertEquals(SettingsPage.PATCH_STATUS, restoredNavigation.getCurrentPage());
        assertArrayEquals(new String[]{"ROOT", "PATCH_STATUS"}, restoredNavigation.serialize());
    }

    @Test
    public void serializationDoesNotExposeMutableNavigationState() {
        SettingsNavigation navigation = new SettingsNavigation();
        navigation.push(SettingsPage.AGENT_I);

        String[] serializedPath = navigation.serialize();
        serializedPath[1] = SettingsPage.CHAT.getId();

        assertEquals(SettingsPage.AGENT_I, navigation.getCurrentPage());
        assertArrayEquals(new String[]{"ROOT", "AGENT_I"}, navigation.serialize());
    }

    private static void assertRestoresRoot(String[] serializedPath) {
        SettingsNavigation navigation = new SettingsNavigation();
        navigation.push(SettingsPage.CHAT);

        navigation.restore(serializedPath);

        assertEquals(SettingsPage.ROOT, navigation.getCurrentPage());
    }
}
