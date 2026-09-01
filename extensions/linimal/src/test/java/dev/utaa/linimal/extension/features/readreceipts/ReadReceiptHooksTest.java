package dev.utaa.linimal.extension.features.readreceipts;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import dev.utaa.linimal.extension.config.ReadReceiptMode;

public final class ReadReceiptHooksTest {
    private static final class EqualSupplier {
        @Override
        public boolean equals(Object other) {
            return other instanceof EqualSupplier;
        }

        @Override
        public int hashCode() {
            return 1;
        }
    }

    @After
    public void clearThreadState() {
        ReadReceiptHooks.clearManualInvocation();
        ReadReceiptHooks.clearPreparedSupplier();
    }

    @Test
    public void normalModeAlwaysPreservesOriginalSending() {
        assertFalse(ReadReceiptHooks.shouldSuppress(ReadReceiptMode.NORMAL, "chat-a"));
    }

    @Test
    public void manualModeSuppressesAutomaticSending() {
        assertTrue(ReadReceiptHooks.shouldSuppress(ReadReceiptMode.MANUAL, "chat-a"));
    }

    @Test
    public void manualSupplierAllowsExactlyOneMatchingSend() {
        Object supplier = new Object();
        ReadReceiptHooks.beginManualInvocation("chat-a");
        ReadReceiptHooks.registerSupplierFromCurrentInvocation(supplier, "chat-a");
        ReadReceiptHooks.prepareSupplier(supplier, "chat-a");

        assertFalse(ReadReceiptHooks.shouldSuppress(ReadReceiptMode.MANUAL, "chat-a"));
        assertTrue(ReadReceiptHooks.shouldSuppress(ReadReceiptMode.MANUAL, "chat-a"));
    }

    @Test
    public void automaticSupplierCannotConsumeAnotherManualRequest() {
        Object manualSupplier = new Object();
        Object automaticSupplier = new Object();
        ReadReceiptHooks.beginManualInvocation("chat-a");
        ReadReceiptHooks.registerSupplierFromCurrentInvocation(manualSupplier, "chat-a");
        ReadReceiptHooks.prepareSupplier(automaticSupplier, "chat-a");

        assertTrue(ReadReceiptHooks.shouldSuppress(ReadReceiptMode.MANUAL, "chat-a"));

        ReadReceiptHooks.prepareSupplier(manualSupplier, "chat-a");
        assertFalse(ReadReceiptHooks.shouldSuppress(ReadReceiptMode.MANUAL, "chat-a"));
    }

    @Test
    public void supplierRegistrationUsesIdentityRatherThanEquals() {
        Object manualSupplier = new EqualSupplier();
        Object equalAutomaticSupplier = new EqualSupplier();
        ReadReceiptHooks.beginManualInvocation("chat-a");
        ReadReceiptHooks.registerSupplierFromCurrentInvocation(manualSupplier, "chat-a");
        ReadReceiptHooks.prepareSupplier(equalAutomaticSupplier, "chat-a");

        assertTrue(ReadReceiptHooks.shouldSuppress(ReadReceiptMode.MANUAL, "chat-a"));

        ReadReceiptHooks.prepareSupplier(manualSupplier, "chat-a");
        assertFalse(ReadReceiptHooks.shouldSuppress(ReadReceiptMode.MANUAL, "chat-a"));
    }

    @Test
    public void mismatchedChatIdDoesNotReceiveTheAllowance() {
        Object supplier = new Object();
        ReadReceiptHooks.beginManualInvocation("chat-a");
        ReadReceiptHooks.registerSupplierFromCurrentInvocation(supplier, "chat-a");
        ReadReceiptHooks.prepareSupplier(supplier, "chat-b");

        assertTrue(ReadReceiptHooks.shouldSuppress(ReadReceiptMode.MANUAL, "chat-b"));
        assertTrue(ReadReceiptHooks.shouldSuppress(ReadReceiptMode.MANUAL, "chat-a"));
    }

    @Test
    public void clearingPreparedSupplierPreventsAStaleAllowance() {
        Object supplier = new Object();
        ReadReceiptHooks.beginManualInvocation("chat-a");
        ReadReceiptHooks.registerSupplierFromCurrentInvocation(supplier, "chat-a");
        ReadReceiptHooks.prepareSupplier(supplier, "chat-a");
        ReadReceiptHooks.clearPreparedSupplier();

        assertTrue(ReadReceiptHooks.shouldSuppress(ReadReceiptMode.MANUAL, "chat-a"));
    }

    @Test
    public void concurrentWorkersCannotExchangeManualAllowances() throws Exception {
        Object firstSupplier = new Object();
        Object secondSupplier = new Object();
        ReadReceiptHooks.beginManualInvocation("chat-a");
        ReadReceiptHooks.registerSupplierFromCurrentInvocation(firstSupplier, "chat-a");
        ReadReceiptHooks.beginManualInvocation("chat-b");
        ReadReceiptHooks.registerSupplierFromCurrentInvocation(secondSupplier, "chat-b");

        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch prepared = new CountDownLatch(2);
        CountDownLatch send = new CountDownLatch(1);
        try {
            Future<Boolean> first = workers.submit(() -> {
                ReadReceiptHooks.prepareSupplier(firstSupplier, "chat-a");
                prepared.countDown();
                send.await(5, TimeUnit.SECONDS);
                return ReadReceiptHooks.shouldSuppress(ReadReceiptMode.MANUAL, "chat-a");
            });
            Future<Boolean> second = workers.submit(() -> {
                ReadReceiptHooks.prepareSupplier(secondSupplier, "chat-b");
                prepared.countDown();
                send.await(5, TimeUnit.SECONDS);
                return ReadReceiptHooks.shouldSuppress(ReadReceiptMode.MANUAL, "chat-b");
            });

            assertTrue(prepared.await(5, TimeUnit.SECONDS));
            send.countDown();
            assertFalse(first.get(5, TimeUnit.SECONDS));
            assertFalse(second.get(5, TimeUnit.SECONDS));
        } finally {
            workers.shutdownNow();
        }
    }
}
