package dev.utaa.linimal.extension.status;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** APK asset に埋め込まれた build-time patch status report の読み取り境界。 */
public final class PatchStatusRepository {
    public static final String ASSET_PATH = "linimal/patch-status.json";
    public static final int MAX_ASSET_BYTES = 64 * 1024;

    private final Context context;
    private final PatchStatusParser parser;

    public PatchStatusRepository(Context context) {
        this(context, new PatchStatusParser());
    }

    PatchStatusRepository(Context context, PatchStatusParser parser) {
        this.context = context;
        this.parser = parser;
    }

    /**
     * asset の不在は unavailable、それ以外の読み取りまたは schema の異常は error として返します。
     * いずれも hook の設定を有効化しない fail-open な結果です。
     */
    public PatchStatusReadResult read() {
        if (context == null) {
            return PatchStatusReadResult.error("Patch status context is unavailable.");
        }
        try (InputStream input = context.getAssets().open(ASSET_PATH)) {
            return read(input, parser);
        } catch (FileNotFoundException exception) {
            return PatchStatusReadResult.unavailable("Patch status asset is unavailable.");
        } catch (IOException exception) {
            return PatchStatusReadResult.error("Patch status asset could not be read.");
        } catch (RuntimeException exception) {
            return PatchStatusReadResult.error("Patch status asset is unavailable.");
        }
    }

    /** Android API に依存しない入力境界。local JVM test から asset 相当の stream を検証できます。 */
    public static PatchStatusReadResult read(InputStream input) {
        return read(input, new PatchStatusParser());
    }

    private static PatchStatusReadResult read(InputStream input, PatchStatusParser parser) {
        if (input == null) {
            return PatchStatusReadResult.error("Patch status input is unavailable.");
        }
        try {
            byte[] bytes = readAtMost(input);
            return PatchStatusReadResult.available(parser.parse(decodeUtf8(bytes)));
        } catch (AssetTooLargeException exception) {
            return PatchStatusReadResult.error("Patch status asset exceeds the 64 KiB limit.");
        } catch (CharacterCodingException | IllegalArgumentException exception) {
            return PatchStatusReadResult.error("Patch status report is invalid.");
        } catch (IOException exception) {
            return PatchStatusReadResult.error("Patch status asset could not be read.");
        } catch (RuntimeException exception) {
            return PatchStatusReadResult.error("Patch status asset could not be read.");
        }
    }

    private static byte[] readAtMost(InputStream input) throws IOException, AssetTooLargeException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        while (output.size() < MAX_ASSET_BYTES) {
            int remaining = MAX_ASSET_BYTES - output.size();
            int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
            if (read < 0) {
                return output.toByteArray();
            }
            if (read == 0) {
                int oneByte = input.read();
                if (oneByte < 0) {
                    return output.toByteArray();
                }
                output.write(oneByte);
            } else {
                output.write(buffer, 0, read);
            }
        }
        if (input.read() >= 0) {
            throw new AssetTooLargeException();
        }
        return output.toByteArray();
    }

    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        CharBuffer characters = decoder.decode(ByteBuffer.wrap(bytes));
        return characters.toString();
    }

    private static final class AssetTooLargeException extends Exception {
    }
}
