package dev.utaa.linimal.extension.features;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Android framework を起動せず hook の fail-open な値変換を検証します。 */
public final class FeatureHooksTest {
    private enum MainTab {
        HOME,
        TIMELINE,
        NEWS,
        NEWS_ROW,
        WALLET,
        OTHER
    }

    private enum ShoppingTab {
        COMMERCE,
        COMMERCE_TW
    }

    private enum ChatItemType {
        CALENDAR,
        GIFT,
        PAY,
        OTHER
    }

    private static final class ChatItem {
        private final ChatItemType type;

        ChatItem(ChatItemType type) {
            this.type = type;
        }
    }

    @Test
    public void mainTabsReturnTheSameListWhenEverySuppressionIsOff() {
        List<MainTab> tabs = Arrays.asList(MainTab.HOME, MainTab.TIMELINE, MainTab.NEWS, MainTab.WALLET);

        List<?> result = MainTabHooks.filterTabsForEnabledStates(tabs, false, false, false, false);

        assertSame(tabs, result);
    }

    @Test
    public void mainTabsPreserveOrderAndOnlyRemoveConfiguredSemanticTabs() {
        List<MainTab> tabs = Arrays.asList(
                MainTab.HOME, MainTab.TIMELINE, MainTab.NEWS, MainTab.NEWS_ROW, MainTab.WALLET);

        List<?> result = MainTabHooks.filterTabsForEnabledStates(tabs, true, true, false, false);

        assertTrue(result.equals(Arrays.asList(MainTab.HOME, MainTab.WALLET)));
    }

    @Test
    public void shoppingSuppressionRemovesBothCommerceVariantsWithoutChangingOtherValues() {
        Object unknownItem = new Object();
        List<Object> tabs = new ArrayList<>(Arrays.asList(
                MainTab.HOME,
                ShoppingTab.COMMERCE,
                null,
                ShoppingTab.COMMERCE_TW,
                MainTab.TIMELINE,
                MainTab.NEWS,
                MainTab.WALLET,
                MainTab.OTHER,
                unknownItem));

        List<?> result = MainTabHooks.filterTabsForEnabledStates(tabs, false, false, false, true);

        assertTrue(tabs.equals(Arrays.asList(
                MainTab.HOME,
                ShoppingTab.COMMERCE,
                null,
                ShoppingTab.COMMERCE_TW,
                MainTab.TIMELINE,
                MainTab.NEWS,
                MainTab.WALLET,
                MainTab.OTHER,
                unknownItem)));
        assertTrue(result.equals(Arrays.asList(
                MainTab.HOME,
                null,
                MainTab.TIMELINE,
                MainTab.NEWS,
                MainTab.WALLET,
                MainTab.OTHER,
                unknownItem)));
        assertSame(tabs.get(0), result.get(0));
        assertSame(tabs.get(8), result.get(6));
    }

    @Test
    public void shoppingAndWalletSuppressionRemainIndependent() {
        List<Object> tabs = Arrays.asList(MainTab.HOME, ShoppingTab.COMMERCE, MainTab.WALLET);

        assertSame(tabs, MainTabHooks.filterTabsForEnabledStates(tabs, false, false, false, false));
        assertTrue(MainTabHooks.filterTabsForEnabledStates(tabs, false, false, false, true)
                .equals(Arrays.asList(MainTab.HOME, MainTab.WALLET)));
        assertTrue(MainTabHooks.filterTabsForEnabledStates(tabs, false, false, true, false)
                .equals(Arrays.asList(MainTab.HOME, ShoppingTab.COMMERCE)));
        assertTrue(MainTabHooks.filterTabsForEnabledStates(tabs, false, false, true, true)
                .equals(Arrays.asList(MainTab.HOME)));
    }

    @Test
    public void chatMenuUsesEnumFieldShapeRatherThanTheConcreteItemClass() {
        assertTrue(ChatMenuHooks.shouldHideForEnabledStates(
                ChatMenuHooks.findMenuItemType(new ChatItem(ChatItemType.CALENDAR)), true, false, false));
        assertTrue(ChatMenuHooks.shouldHideForEnabledStates("GIFT", false, true, false));
        assertTrue(ChatMenuHooks.shouldHideForEnabledStates("PAY", false, false, true));
        assertFalse(ChatMenuHooks.shouldHideForEnabledStates("OTHER", true, true, true));
    }

    @Test
    public void lineAiOnlyChangesAnEnabledOriginalValueWhenSuppressionIsOn() {
        assertTrue(LineAiHooks.adjustVisibilityForSuppression(true, false));
        assertFalse(LineAiHooks.adjustVisibilityForSuppression(true, true));
        assertFalse(LineAiHooks.adjustVisibilityForSuppression(false, true));
    }

    @Test
    public void smartChannelOnlySuppressesWhenCleanupCanSucceed() {
        Object renderer = new Object();
        assertTrue(SmartChannelHooks.shouldSuppressWith(true, null, ignored -> false));
        assertTrue(SmartChannelHooks.shouldSuppressWith(true, renderer, ignored -> true));
        assertFalse(SmartChannelHooks.shouldSuppressWith(true, renderer, ignored -> false));
        assertFalse(SmartChannelHooks.shouldSuppressWith(false, renderer, ignored -> true));

        assertNull(SmartChannelHooks.rendererForBindingWith(
                true, renderer, ignored -> true));
        assertSame(renderer, SmartChannelHooks.rendererForBindingWith(
                true, renderer, ignored -> false));
        assertSame(renderer, SmartChannelHooks.rendererForBindingWith(
                false, renderer, ignored -> true));
    }

    @Test
    public void tabFilteringDoesNotMutateTheCallerList() {
        List<MainTab> mutable = new ArrayList<>(Arrays.asList(MainTab.HOME, MainTab.WALLET));

        MainTabHooks.filterTabsForEnabledStates(mutable, false, false, true, false);

        assertTrue(mutable.equals(Arrays.asList(MainTab.HOME, MainTab.WALLET)));
    }
}
