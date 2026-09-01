package dev.utaa.linimal.extension.features;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/** Home Performance Ad の Flow item gate が OFF 時に元の list を保持することを検証します。 */
public final class HomeTopAdHooksTest {
    @Test
    public void offPreservesTheOriginalListIdentity() {
        List<Object> original = new ArrayList<>(Arrays.asList(new Object()));

        List<?> result = HomeTopAdHooks.filterPerformanceAdItemsForEnabledState(original, false);

        assertSame(original, result);
        assertEquals(1, result.size());
    }

    @Test
    public void onDropsOnlyTheDedicatedPerformanceAdFlowItemsWithoutMutatingInput() {
        List<Object> original = new ArrayList<>(Arrays.asList(new Object()));

        List<?> result = HomeTopAdHooks.filterPerformanceAdItemsForEnabledState(original, true);

        assertEquals(0, result.size());
        assertEquals(1, original.size());
    }

    @Test
    public void nullInputRemainsUnchangedForFailOpenCompatibility() {
        assertSame(null, HomeTopAdHooks.filterPerformanceAdItemsForEnabledState(null, true));
    }

    @Test
    public void catalogGateDropsOnlyTheMiddleAndBottomPerformanceAds() {
        Object lan = new Object();
        Object notification = new Object();
        Object social = new Object();
        Object services = new Object();
        Object recentlyUpdated = new Object();
        List<Object> original = Arrays.<Object>asList(
                lan,
                notification,
                social,
                services,
                new DefaultModule("home-content-server_home-performance-ad-middle"),
                recentlyUpdated,
                new DefaultModule("home-content-server_home-performance-ad-bottom"));

        List<?> result = HomeTopAdHooks.filterHomePerformanceAdCatalogItemsForEnabledState(
                original, true);

        assertEquals(5, result.size());
        assertSame(lan, result.get(0));
        assertSame(notification, result.get(1));
        assertSame(social, result.get(2));
        assertSame(services, result.get(3));
        assertSame(recentlyUpdated, result.get(4));
        assertEquals(7, original.size());
    }

    @Test
    public void catalogGateFailsOpenForUnexpectedSizeOrPositions() {
        List<Object> wrongSize = Arrays.<Object>asList(
                new Object(), new Object(), new Object(), new Object(),
                new DefaultModule("home-content-server_home-performance-ad-middle"),
                new Object());
        List<Object> wrongPosition = new ArrayList<>(Arrays.<Object>asList(
                new Object(), new Object(), new Object(), new Object(),
                new DefaultModule("home-content-server_home-performance-ad-bottom"),
                new Object(),
                new DefaultModule("home-content-server_home-performance-ad-middle")));

        assertSame(wrongSize,
                HomeTopAdHooks.filterHomePerformanceAdCatalogItemsForEnabledState(wrongSize, true));
        assertSame(wrongPosition,
                HomeTopAdHooks.filterHomePerformanceAdCatalogItemsForEnabledState(wrongPosition, true));
    }

    @Test
    public void catalogGateOffPreservesTheOriginalListIdentity() {
        List<Object> original = Arrays.<Object>asList(new Object(), new Object());

        assertSame(original,
                HomeTopAdHooks.filterHomePerformanceAdCatalogItemsForEnabledState(original, false));
    }

    private static final class DefaultModule {
        private final String id;

        DefaultModule(String id) {
            this.id = id;
        }

        @Override
        public String toString() {
            return "GcsDefaultModule(id=" + id
                    + ", name=home_performance_ad, payload=payload)";
        }
    }
}
