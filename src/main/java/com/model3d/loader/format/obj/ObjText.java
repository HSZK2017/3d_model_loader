package com.model3d.loader.format.obj;

import java.util.ArrayList;
import java.util.List;

/**
 * Line pre-processing shared by the OBJ and MTL readers.
 *
 * <p>Both formats are line-oriented, ASCII-era text and both are emitted by tools that disagree
 * about the details: CRLF from Windows exporters, a UTF-8 BOM from editors that "helpfully" add
 * one, {@code #} comments on their own line or trailing a statement, and - the one that is usually
 * forgotten - a trailing {@code \} continuing a statement on the next physical line, which Blender
 * writes for long {@code f} lines. Collapsing continuations here means every reader below sees one
 * statement per logical line and never has to know about any of it.
 *
 * <p>The physical line number of the <b>first</b> line of a statement is kept, so a diagnostic
 * points at the line the user sees in an editor and not at wherever a continuation happened to end.
 */
final class ObjText {

    /** One logical statement: the number of its first physical line, and its text. */
    record Statement(int line, String text) {

        /** Whitespace-separated tokens. Never empty, never contains an empty token. */
        String[] tokens() {
            return text.split("\\s+");
        }

        /**
         * Everything after the leading keyword, trimmed. Used for {@code o}/{@code g}/{@code usemtl}/
         * {@code newmtl}, where the name may legally contain spaces and must not be truncated to its
         * first token.
         */
        String remainder() {
            int split = 0;
            while (split < text.length() && !Character.isWhitespace(text.charAt(split))) {
                split++;
            }
            return text.substring(split).trim();
        }
    }

    private ObjText() {
    }

    /**
     * Splits raw file text into logical statements.
     *
     * <p>A statement is a physical line with its comment removed, plus every following line that the
     * previous one continued into with a trailing backslash. Blank and comment-only lines produce no
     * statement.
     */
    static List<Statement> statements(String text) {
        List<Statement> statements = new ArrayList<>();
        StringBuilder joined = new StringBuilder();
        int joinedLine = 0;
        int continuing = 0; // 0 = no continuation pending
        int lineNumber = 0;
        for (String raw : text.split("\n", -1)) {
            lineNumber++;
            // strip() removes the CR of a CRLF pair and the leading indentation of a continuation.
            String line = stripComment(raw).strip();
            if (line.endsWith("\\")) {
                if (continuing == 0) {
                    joined.setLength(0);
                    joinedLine = lineNumber;
                }
                joined.append(line, 0, line.length() - 1).append(' ');
                continuing++;
                continue;
            }
            if (continuing > 0) {
                joined.append(line);
                statements.add(new Statement(joinedLine, joined.toString().strip()));
                continuing = 0;
            } else if (!line.isEmpty()) {
                statements.add(new Statement(lineNumber, line));
            }
        }
        if (continuing > 0) {
            // A file ending mid-continuation still describes something; keep it and let the reader
            // decide, rather than dropping the last statement of a truncated download.
            statements.add(new Statement(joinedLine, joined.toString().strip()));
        }
        return statements;
    }

    /**
     * Removes a {@code #} comment.
     *
     * <p>Only a {@code #} at the start of the line or preceded by whitespace starts a comment:
     * {@code map_Kd tex#1.png} is a filename, {@code f 1 2 3 # front face} is a comment.
     */
    private static String stripComment(String line) {
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == '#' && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
                return line.substring(0, i);
            }
        }
        return line;
    }
}
