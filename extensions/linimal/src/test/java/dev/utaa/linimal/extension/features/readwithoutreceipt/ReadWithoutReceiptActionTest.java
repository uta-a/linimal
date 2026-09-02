package dev.utaa.linimal.extension.features.readwithoutreceipt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

public final class ReadWithoutReceiptActionTest {
    @After
    public void resetState() {
        ChatListMenuHooks.resetForTesting();
        ReadWithoutReceiptHooks.resetForTesting();
    }

    /** 難読化された Kotlin {@code Function0} を模した fixture。reflection の対象になります。 */
    public static final class FakeDismiss {
        int invocations;

        public Object invoke() {
            invocations++;
            return null;
        }
    }

    /** {@code invoke()} が失敗するラムダ。メニューが閉じられない場合の fail-open を確認します。 */
    public static final class ThrowingDismiss {
        int invocations;

        public Object invoke() {
            invocations++;
            throw new IllegalStateException("simulated dismiss failure");
        }
    }

    @Test
    public void invokeNeverThrowsWithoutADismissLambda() {
        // application context も無いため、invoke() は何もせず Unit 相当を返して終わります。
        new ReadWithoutReceiptAction("chat-1", null).invoke();
    }

    @Test
    public void invokeNeverThrowsWithoutAChatId() {
        new ReadWithoutReceiptAction(null, null).invoke();
        new ReadWithoutReceiptAction("", null).invoke();
    }

    @Test
    public void invokeClosesTheMenuThroughTheDismissLambda() {
        FakeDismiss dismiss = new FakeDismiss();

        new ReadWithoutReceiptAction("chat-1", dismiss).invoke();

        assertEquals(1, dismiss.invocations);
    }

    @Test
    public void invokeContinuesWhenTheDismissLambdaThrows() {
        ThrowingDismiss dismiss = new ThrowingDismiss();

        // 例外は外へ漏れず、invoke() は最後まで到達します。
        new ReadWithoutReceiptAction("chat-1", dismiss).invoke();

        assertEquals(1, dismiss.invocations);
    }

    @Test
    public void dismissMenuIgnoresAnObjectWithoutAnInvokeMethod() {
        // 難読化の形が想定と違っても、メニューが閉じないだけで例外は漏れません。
        ReadWithoutReceiptAction.dismissMenu(new Object());
        ReadWithoutReceiptAction.dismissMenu(null);
    }

    @Test
    public void theChatRoomUriCarriesOnlyTheChatId() {
        // 種別の判定は不要で、LINE の scheme handler が ID から解決します。
        assertEquals("line://nv/openChatroom/?id=u0123456789", ReadWithoutReceiptAction.chatRoomUri("u0123456789"));
        assertTrue(ReadWithoutReceiptAction.chatRoomUri("x").startsWith("line://nv/openChatroom/?id="));
    }
}
