package com.model3d.loader.format.json;

import java.io.IOException;
import java.io.Reader;
import java.util.Arrays;

/**
 * The lexer half of the JSON reader: turns characters into tokens.
 *
 * <p>Written for real exporter output rather than only for well-formed test fixtures, which is why
 * it does three things a strict RFC 8259 reader would not:
 * <ul>
 *   <li>A leading byte-order mark is skipped. Windows tooling writes {@code EF BB BF} at the head of
 *       a {@code .gltf} often enough that failing on it would reject real files.</li>
 *   <li>NUL is treated as whitespace. The GLB spec pads the JSON chunk to a 4-byte boundary with
 *       spaces and several tools pad with {@code 0x00}; a raw NUL outside a string cannot occur in a
 *       valid document, so accepting it as padding cannot change the meaning of a valid file.</li>
 *   <li>Errors carry the line, column and character offset of the failing token, because
 *       "expected ','" without a position is useless in a 22 000-character document.</li>
 * </ul>
 *
 * <p>Numbers are parsed strictly (no leading zero, no trailing '.', no bare 'e') and rejected when
 * they are not representable as a finite {@code double}: {@code 1e999} in a glTF field is broken
 * input, and letting it become {@code Infinity} would propagate into every matrix that touches it.
 *
 * <p>The scanners reuse internal char buffers rather than building a {@code StringBuilder} per
 * token, so reading a multi-megabyte {@code .gltf} does not turn into a garbage-collection test.
 * Not thread-safe and not reusable; {@link JsonParser} creates one per document.
 */
final class JsonTokenizer {

    enum Token {
        BEGIN_OBJECT,
        END_OBJECT,
        BEGIN_ARRAY,
        END_ARRAY,
        COLON,
        COMMA,
        STRING,
        NUMBER,
        TRUE,
        FALSE,
        NULL,
        EOF
    }

    private final Reader in;
    private final String source;
    private final char[] buffer = new char[8192];

    private int bufferLength;
    private int bufferPosition;
    private boolean endOfInput;

    /** Characters consumed so far; the offset of the next character to read. */
    private long consumed;
    private int line = 1;
    private int column;

    private Token token = Token.EOF;
    private long tokenOffset;
    private int tokenLine = 1;
    private int tokenColumn;

    private char[] textBuffer = new char[64];
    private String stringValue;

    private double numberValue;
    private boolean numberIntegral;
    private boolean numberFitsLong;
    private long numberLong;

    JsonTokenizer(Reader in, String source) {
        this.in = in;
        this.source = source;
    }

    Token current() {
        return token;
    }

    /** Advances to the next token; returns {@link Token#EOF} (repeatedly) at the end of input. */
    Token next() throws JsonParseException {
        try {
            int c = skipWhitespace();
            tokenOffset = consumed;
            tokenLine = line;
            tokenColumn = column + 1;
            if (c < 0) {
                token = Token.EOF;
                return token;
            }
            switch (c) {
                case '{':
                    take();
                    token = Token.BEGIN_OBJECT;
                    return token;
                case '}':
                    take();
                    token = Token.END_OBJECT;
                    return token;
                case '[':
                    take();
                    token = Token.BEGIN_ARRAY;
                    return token;
                case ']':
                    take();
                    token = Token.END_ARRAY;
                    return token;
                case ':':
                    take();
                    token = Token.COLON;
                    return token;
                case ',':
                    take();
                    token = Token.COMMA;
                    return token;
                case '"':
                    take();
                    readString();
                    token = Token.STRING;
                    return token;
                case 't':
                    readLiteral("true");
                    token = Token.TRUE;
                    return token;
                case 'f':
                    readLiteral("false");
                    token = Token.FALSE;
                    return token;
                case 'n':
                    readLiteral("null");
                    token = Token.NULL;
                    return token;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        readNumber();
                        token = Token.NUMBER;
                        return token;
                    }
                    throw error("unexpected character '" + printable(c) + "' (U+"
                            + String.format("%04X", c) + ")");
            }
        } catch (IOException e) {
            throw new JsonParseException(source + ": I/O error while reading the document: "
                    + e.getMessage(), e);
        }
    }

    String string() {
        return stringValue;
    }

    double number() {
        return numberValue;
    }

    boolean numberIsIntegral() {
        return numberIntegral;
    }

    boolean numberFitsLong() {
        return numberFitsLong;
    }

    long numberLong() {
        return numberLong;
    }

    /** Character offset of the current token's first character. */
    long tokenOffset() {
        return tokenOffset;
    }

    /** Current read head, for "whatever comes next is wrong" errors. */
    long offset() {
        return consumed;
    }

    JsonParseException error(String detail) {
        return new JsonParseException(source + ":" + line + ":" + (column + 1)
                + " (character " + consumed + "): " + detail);
    }

    /** Error positioned at the token the parser is looking at, rather than at the read head. */
    JsonParseException tokenError(String detail) {
        return new JsonParseException(source + ":" + tokenLine + ":" + tokenColumn
                + " (character " + tokenOffset + "): " + detail);
    }

    String describeToken() {
        switch (token) {
            case EOF:
                return "end of input";
            case BEGIN_OBJECT:
                return "'{'";
            case END_OBJECT:
                return "'}'";
            case BEGIN_ARRAY:
                return "'['";
            case END_ARRAY:
                return "']'";
            case COLON:
                return "':'";
            case COMMA:
                return "','";
            case STRING:
                return "string " + quoteForMessage(stringValue);
            case NUMBER:
                return "number " + numberValue;
            case TRUE:
                return "true";
            case FALSE:
                return "false";
            case NULL:
                return "null";
            default:
                return token.toString();
        }
    }

    private static String quoteForMessage(String value) {
        String shown = value.length() > 40 ? value.substring(0, 40) + "..." : value;
        return '"' + shown + '"';
    }

    private static String printable(int c) {
        if (c >= 0x20 && c < 0x7F) {
            return String.valueOf((char) c);
        }
        return "\\u" + String.format("%04X", c);
    }

    private int skipWhitespace() throws IOException {
        while (true) {
            int c = peek();
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == 0) {
                take();
                continue;
            }
            if (c == 0xFEFF && consumed == 0) {
                take();
                continue;
            }
            return c;
        }
    }

    private void readLiteral(String literal) throws IOException, JsonParseException {
        for (int i = 0; i < literal.length(); i++) {
            int c = take();
            if (c != literal.charAt(i)) {
                throw error("expected '" + literal + "'");
            }
        }
        int c = peek();
        boolean identifierChar = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '_';
        if (identifierChar) {
            throw error("expected '" + literal + "'");
        }
    }

    private void readString() throws IOException, JsonParseException {
        int length = 0;
        while (true) {
            int c = take();
            if (c < 0) {
                throw error("unterminated string literal");
            }
            if (c == '"') {
                break;
            }
            if (c == '\\') {
                int escape = take();
                if (escape < 0) {
                    throw error("unterminated string literal");
                }
                switch (escape) {
                    case '"':
                        c = '"';
                        break;
                    case '\\':
                        c = '\\';
                        break;
                    case '/':
                        c = '/';
                        break;
                    case 'b':
                        c = '\b';
                        break;
                    case 'f':
                        c = '\f';
                        break;
                    case 'n':
                        c = '\n';
                        break;
                    case 'r':
                        c = '\r';
                        break;
                    case 't':
                        c = '\t';
                        break;
                    case 'u':
                        c = readHexQuad();
                        break;
                    default:
                        throw error("invalid escape sequence '\\" + printable(escape) + "'");
                }
            } else if (c < 0x20) {
                // Only a character taken literally from the input is checked: an escaped \n is
                // legal and must not be mistaken for a raw control character.
                throw error("unescaped control character U+" + String.format("%04X", c)
                        + " in a string literal");
            }
            if (length == textBuffer.length) {
                textBuffer = Arrays.copyOf(textBuffer, growLength(textBuffer.length));
            }
            textBuffer[length++] = (char) c;
        }
        stringValue = new String(textBuffer, 0, length);
    }

    private static int growLength(int current) {
        // Grow by half once the buffer is large: a base64 buffer uri is a single multi-megabyte
        // string, and doubling from 64 bytes there wastes as much as it copies.
        return current >= 1 << 20 ? current + (current >> 1) : current * 2;
    }

    private int readHexQuad() throws IOException, JsonParseException {
        int value = 0;
        for (int i = 0; i < 4; i++) {
            int c = take();
            int digit;
            if (c >= '0' && c <= '9') {
                digit = c - '0';
            } else if (c >= 'a' && c <= 'f') {
                digit = c - 'a' + 10;
            } else if (c >= 'A' && c <= 'F') {
                digit = c - 'A' + 10;
            } else {
                throw error("invalid unicode escape: expected four hexadecimal digits");
            }
            value = (value << 4) | digit;
        }
        return value;
    }

    private void readNumber() throws IOException, JsonParseException {
        int length = 0;
        boolean integral = true;
        int c = peek();
        if (c == '-') {
            length = put(length, '-');
            c = peek();
        }
        if (c == '0') {
            length = put(length, '0');
            c = peek();
            if (c >= '0' && c <= '9') {
                throw error("invalid number: leading zero");
            }
        } else if (c >= '1' && c <= '9') {
            while (c >= '0' && c <= '9') {
                length = put(length, (char) c);
                c = peek();
            }
        } else {
            throw error("invalid number: expected a digit");
        }
        if (c == '.') {
            integral = false;
            length = put(length, '.');
            c = peek();
            if (c < '0' || c > '9') {
                throw error("invalid number: expected a digit after '.'");
            }
            while (c >= '0' && c <= '9') {
                length = put(length, (char) c);
                c = peek();
            }
        }
        if (c == 'e' || c == 'E') {
            integral = false;
            length = put(length, (char) c);
            c = peek();
            if (c == '+' || c == '-') {
                length = put(length, (char) c);
                c = peek();
            }
            if (c < '0' || c > '9') {
                throw error("invalid number: expected a digit in the exponent");
            }
            while (c >= '0' && c <= '9') {
                length = put(length, (char) c);
                c = peek();
            }
        }
        if (c >= 0 && (Character.isLetter(c) || c == '.' || c == '_')) {
            throw error("invalid character '" + printable(c) + "' after a number");
        }

        String literal = new String(textBuffer, 0, length);
        numberValue = Double.parseDouble(literal);
        if (!Double.isFinite(numberValue)) {
            throw error("number " + literal + " is not representable as a finite double");
        }
        numberIntegral = integral;
        numberFitsLong = false;
        numberLong = 0;
        if (integral) {
            try {
                numberLong = Long.parseLong(literal);
                numberFitsLong = true;
            } catch (NumberFormatException tooLarge) {
                // Kept as a double; asLong() reports the overflow only if a field needs it.
                numberFitsLong = false;
            }
        }
    }

    /** Appends {@code c} to the number buffer, consumes it from the input, returns the new length. */
    private int put(int length, char c) throws IOException {
        if (length == textBuffer.length) {
            textBuffer = Arrays.copyOf(textBuffer, growLength(textBuffer.length));
        }
        textBuffer[length] = c;
        take();
        return length + 1;
    }

    private int peek() throws IOException {
        if (bufferPosition >= bufferLength && !fill()) {
            return -1;
        }
        return buffer[bufferPosition];
    }

    private int take() throws IOException {
        int c = peek();
        if (c < 0) {
            return -1;
        }
        bufferPosition++;
        consumed++;
        if (c == '\n') {
            line++;
            column = 0;
        } else {
            column++;
        }
        return c;
    }

    private boolean fill() throws IOException {
        if (endOfInput) {
            return false;
        }
        bufferLength = in.read(buffer, 0, buffer.length);
        bufferPosition = 0;
        if (bufferLength <= 0) {
            bufferLength = 0;
            endOfInput = true;
            return false;
        }
        return true;
    }
}
