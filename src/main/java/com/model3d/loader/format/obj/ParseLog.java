package com.model3d.loader.format.obj;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Diagnostic sink for the OBJ/MTL readers.
 *
 * <p>The indirection exists for two concrete reasons. Tests must be able to assert <i>what</i> was
 * reported and <i>how often</i> - "one warning per distinct unknown material" is otherwise
 * unobservable, and a parser that screams once per face about the same missing texture is a real
 * bug. And a mod that loads untrusted third-party files has nothing else to show a user: an OBJ
 * that renders untextured must say which file it wanted.
 *
 * <p>{@link #slf4j} is the production implementation, so the messages land in the game log with the
 * rest of the mod. The interface itself keeps this package free of any logging framework in its
 * signatures.
 */
interface ParseLog {

    void debug(String message);

    void warn(String message);

    /** Discards everything; for callers that only want the parsed data. */
    ParseLog SILENT = new ParseLog() {
        @Override
        public void debug(String message) {
        }

        @Override
        public void warn(String message) {
        }
    };

    static ParseLog slf4j(Class<?> owner) {
        Logger logger = LoggerFactory.getLogger(owner);
        return new ParseLog() {
            @Override
            public void debug(String message) {
                logger.debug(message);
            }

            @Override
            public void warn(String message) {
                logger.warn(message);
            }
        };
    }
}
