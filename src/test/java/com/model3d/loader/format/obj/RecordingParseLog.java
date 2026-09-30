package com.model3d.loader.format.obj;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link ParseLog} that remembers what it was told.
 *
 * <p>Needed because several of this parser's contracts are about diagnostics rather than data: one
 * warning per distinct unknown material, one per unresolved texture, a debug count of dropped
 * degenerate triangles. Those are unobservable through the returned scene, and a chatty parser that
 * warns once per face is a real defect.
 */
final class RecordingParseLog implements ParseLog {

    private final List<String> debugMessages = new ArrayList<>();
    private final List<String> warnMessages = new ArrayList<>();

    @Override
    public void debug(String message) {
        debugMessages.add(message);
    }

    @Override
    public void warn(String message) {
        warnMessages.add(message);
    }

    List<String> debugMessages() {
        return List.copyOf(debugMessages);
    }

    List<String> warnMessages() {
        return List.copyOf(warnMessages);
    }

    /** How many warnings mention {@code fragment}, case-insensitively. */
    int warnCount(String fragment) {
        return count(warnMessages, fragment);
    }

    boolean warned(String fragment) {
        return warnCount(fragment) > 0;
    }

    boolean debugged(String fragment) {
        return count(debugMessages, fragment) > 0;
    }

    /** First warning mentioning {@code fragment}, for message assertions; "" when there is none. */
    String firstWarning(String fragment) {
        for (String message : warnMessages) {
            if (message.toLowerCase().contains(fragment.toLowerCase())) {
                return message;
            }
        }
        return "";
    }

    /** Prints everything so a failing run shows what the parser actually said. */
    void print(String testName) {
        System.out.println("[obj-test] " + testName + ": " + debugMessages.size() + " debug, "
                + warnMessages.size() + " warn");
        for (String message : debugMessages) {
            System.out.println("    D " + message);
        }
        for (String message : warnMessages) {
            System.out.println("    W " + message);
        }
    }

    private static int count(List<String> messages, String fragment) {
        int total = 0;
        for (String message : messages) {
            if (message.toLowerCase().contains(fragment.toLowerCase())) {
                total++;
            }
        }
        return total;
    }
}
