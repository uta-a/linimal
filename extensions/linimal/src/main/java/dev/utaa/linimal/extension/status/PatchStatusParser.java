package dev.utaa.linimal.extension.status;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 外部 JSON 依存なしで patch-status schema v1 を厳密に検証する parser。 */
public final class PatchStatusParser {
    public static final int SCHEMA_VERSION = 1;

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final Set<String> REPORT_FIELDS = fields("schemaVersion", "patches");
    private static final Set<String> PATCH_FIELDS = fields(
            "patchId",
            "featureId",
            "status",
            "expectedTargetCount",
            "actualTargetCount",
            "reason");
    private static final Set<String> REQUIRED_PATCH_FIELDS = fields(
            "patchId",
            "featureId",
            "status",
            "expectedTargetCount",
            "actualTargetCount");

    /**
     * JSON text を解析します。schema に適合しない入力は IllegalArgumentException で拒否します。
     * repository はこの例外を捕捉して runtime を fail-open に保ちます。
     */
    public PatchStatusReport parse(String json) {
        if (json == null) {
            throw new IllegalArgumentException("Patch status JSON is unavailable");
        }

        Object value = new JsonReader(json).readDocument();
        Map<String, Object> report = requireObject(value, "report");
        requireExactFields(report, REPORT_FIELDS, REPORT_FIELDS, "report");

        int schemaVersion = requireNonNegativeInt(report.get("schemaVersion"), "schemaVersion");
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported patch status schema version");
        }

        List<Object> patchValues = requireArray(report.get("patches"), "patches");
        List<PatchStatusRecord> patches = new ArrayList<>();
        Set<String> patchIds = new HashSet<>();
        for (Object patchValue : patchValues) {
            Map<String, Object> patch = requireObject(patchValue, "patch");
            requireExactFields(patch, PATCH_FIELDS, REQUIRED_PATCH_FIELDS, "patch");

            String patchId = requireIdentifier(patch.get("patchId"), "patchId");
            if (!patchIds.add(patchId)) {
                throw new IllegalArgumentException("Duplicate patchId");
            }

            String featureId = requireIdentifier(patch.get("featureId"), "featureId");
            String statusValue = requireString(patch.get("status"), "status");
            PatchStatus status;
            try {
                status = PatchStatus.valueOf(statusValue);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Unknown patch status");
            }

            int expectedTargetCount = requireNonNegativeInt(
                    patch.get("expectedTargetCount"),
                    "expectedTargetCount");
            int actualTargetCount = requireNonNegativeInt(
                    patch.get("actualTargetCount"),
                    "actualTargetCount");
            validateStatusCounts(status, expectedTargetCount, actualTargetCount);
            String reason = patch.containsKey("reason")
                    ? requireString(patch.get("reason"), "reason")
                    : null;

            patches.add(new PatchStatusRecord(
                    patchId,
                    featureId,
                    status,
                    expectedTargetCount,
                    actualTargetCount,
                    reason));
        }

        return new PatchStatusReport(schemaVersion, patches);
    }

    private static Set<String> fields(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    private static void requireExactFields(
            Map<String, Object> object,
            Set<String> knownFields,
            Set<String> requiredFields,
            String name) {
        if (!knownFields.containsAll(object.keySet()) || !object.keySet().containsAll(requiredFields)) {
            throw new IllegalArgumentException("Invalid " + name + " fields");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requireObject(Object value, String name) {
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("Invalid " + name + " type");
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> requireArray(Object value, String name) {
        if (!(value instanceof List)) {
            throw new IllegalArgumentException("Invalid " + name + " type");
        }
        return (List<Object>) value;
    }

    private static String requireString(Object value, String name) {
        if (!(value instanceof String)) {
            throw new IllegalArgumentException("Invalid " + name + " type");
        }
        return (String) value;
    }

    private static String requireIdentifier(Object value, String name) {
        String identifier = requireString(value, name);
        if (!IDENTIFIER_PATTERN.matcher(identifier).matches()) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        return identifier;
    }

    private static int requireNonNegativeInt(Object value, String name) {
        if (!(value instanceof JsonNumber)) {
            throw new IllegalArgumentException("Invalid " + name + " type");
        }
        String raw = ((JsonNumber) value).raw;
        if (!raw.matches("0|[1-9][0-9]*")) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid " + name);
        }
    }

    /** build-time collector と同じ count 規則を report の信頼境界でも検証します。 */
    private static void validateStatusCounts(
            PatchStatus status,
            int expectedTargetCount,
            int actualTargetCount) {
        if (status == PatchStatus.DISABLED) {
            if (expectedTargetCount != 0 || actualTargetCount != 0) {
                throw new IllegalArgumentException("DISABLED patch must have zero target counts");
            }
            return;
        }

        PatchStatus expectedStatus;
        if (actualTargetCount > expectedTargetCount) {
            expectedStatus = PatchStatus.ERROR;
        } else if (actualTargetCount == expectedTargetCount) {
            expectedStatus = PatchStatus.OK;
        } else if (actualTargetCount == 0) {
            expectedStatus = PatchStatus.TARGET_NOT_FOUND;
        } else {
            expectedStatus = PatchStatus.PARTIAL;
        }
        if (status != expectedStatus) {
            throw new IllegalArgumentException("Patch status does not match target counts");
        }
    }

    private static final class JsonNumber {
        private final String raw;

        JsonNumber(String raw) {
            this.raw = raw;
        }
    }

    /** JSON schema の検証対象を限定しても構文自体は完全に読み切る小さな JSON reader。 */
    private static final class JsonReader {
        private static final int MAX_NESTING = 32;

        private final String source;
        private int index;

        JsonReader(String source) {
            this.source = source;
        }

        Object readDocument() {
            skipWhitespace();
            Object value = readValue(0);
            skipWhitespace();
            if (index != source.length()) {
                throw invalidJson();
            }
            return value;
        }

        private Object readValue(int nesting) {
            if (nesting > MAX_NESTING || index >= source.length()) {
                throw invalidJson();
            }
            char character = source.charAt(index);
            switch (character) {
                case '{':
                    return readObject(nesting + 1);
                case '[':
                    return readArray(nesting + 1);
                case '"':
                    return readString();
                case 't':
                    readLiteral("true");
                    return Boolean.TRUE;
                case 'f':
                    readLiteral("false");
                    return Boolean.FALSE;
                case 'n':
                    readLiteral("null");
                    return null;
                default:
                    if (character == '-' || (character >= '0' && character <= '9')) {
                        return readNumber();
                    }
                    throw invalidJson();
            }
        }

        private Map<String, Object> readObject(int nesting) {
            index++;
            skipWhitespace();
            Map<String, Object> values = new LinkedHashMap<>();
            if (consume('}')) {
                return values;
            }
            while (true) {
                skipWhitespace();
                if (index >= source.length() || source.charAt(index) != '"') {
                    throw invalidJson();
                }
                String key = readString();
                if (values.containsKey(key)) {
                    throw new IllegalArgumentException("Duplicate JSON field");
                }
                skipWhitespace();
                require(':');
                skipWhitespace();
                values.put(key, readValue(nesting));
                skipWhitespace();
                if (consume('}')) {
                    return values;
                }
                require(',');
            }
        }

        private List<Object> readArray(int nesting) {
            index++;
            skipWhitespace();
            List<Object> values = new ArrayList<>();
            if (consume(']')) {
                return values;
            }
            while (true) {
                skipWhitespace();
                values.add(readValue(nesting));
                skipWhitespace();
                if (consume(']')) {
                    return values;
                }
                require(',');
            }
        }

        private String readString() {
            require('"');
            StringBuilder value = new StringBuilder();
            while (index < source.length()) {
                char character = source.charAt(index++);
                if (character == '"') {
                    return value.toString();
                }
                if (character <= 0x1f) {
                    throw invalidJson();
                }
                if (character != '\\') {
                    value.append(character);
                    continue;
                }
                if (index >= source.length()) {
                    throw invalidJson();
                }
                char escaped = source.charAt(index++);
                switch (escaped) {
                    case '"':
                    case '\\':
                    case '/':
                        value.append(escaped);
                        break;
                    case 'b':
                        value.append('\b');
                        break;
                    case 'f':
                        value.append('\f');
                        break;
                    case 'n':
                        value.append('\n');
                        break;
                    case 'r':
                        value.append('\r');
                        break;
                    case 't':
                        value.append('\t');
                        break;
                    case 'u':
                        value.append(readUnicodeEscape());
                        break;
                    default:
                        throw invalidJson();
                }
            }
            throw invalidJson();
        }

        private char readUnicodeEscape() {
            if (index + 4 > source.length()) {
                throw invalidJson();
            }
            int codePoint = 0;
            for (int offset = 0; offset < 4; offset++) {
                int digit = Character.digit(source.charAt(index++), 16);
                if (digit < 0) {
                    throw invalidJson();
                }
                codePoint = (codePoint << 4) | digit;
            }
            return (char) codePoint;
        }

        private JsonNumber readNumber() {
            int start = index;
            consume('-');
            if (consume('0')) {
                // JSON では leading zero を許可しません。
            } else {
                requireDigitOneToNine();
                while (consumeDigit()) {
                    // 整数部を読み進めます。
                }
            }
            if (consume('.')) {
                requireDigit();
                while (consumeDigit()) {
                    // 小数部を読み進めます。
                }
            }
            if (consume('e') || consume('E')) {
                consume('+');
                consume('-');
                requireDigit();
                while (consumeDigit()) {
                    // 指数部を読み進めます。
                }
            }
            return new JsonNumber(source.substring(start, index));
        }

        private void readLiteral(String literal) {
            if (!source.regionMatches(index, literal, 0, literal.length())) {
                throw invalidJson();
            }
            index += literal.length();
        }

        private void skipWhitespace() {
            while (index < source.length()) {
                char character = source.charAt(index);
                if (character != ' ' && character != '\n' && character != '\r' && character != '\t') {
                    return;
                }
                index++;
            }
        }

        private boolean consume(char expected) {
            if (index < source.length() && source.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        private void require(char expected) {
            if (!consume(expected)) {
                throw invalidJson();
            }
        }

        private boolean consumeDigit() {
            if (index >= source.length()) {
                return false;
            }
            char character = source.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
            index++;
            return true;
        }

        private void requireDigit() {
            if (!consumeDigit()) {
                throw invalidJson();
            }
        }

        private void requireDigitOneToNine() {
            if (index >= source.length()) {
                throw invalidJson();
            }
            char character = source.charAt(index);
            if (character < '1' || character > '9') {
                throw invalidJson();
            }
            index++;
        }

        private IllegalArgumentException invalidJson() {
            return new IllegalArgumentException("Malformed patch status JSON");
        }
    }
}
