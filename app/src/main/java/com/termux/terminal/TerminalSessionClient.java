package com.termux.terminal;

/*
 * Modified for RemBrowse: trimmed to what TerminalEmulator and Logger use. Termux's version also
 * carries callbacks about its local-process TerminalSession, which RemBrowse does not include
 * (its terminals talk to SSH servers instead).
 */

/** The client of a {@link TerminalEmulator}: cursor style and logging. */
public interface TerminalSessionClient {

    void onTerminalCursorStateChange(boolean state);

    Integer getTerminalCursorStyle();

    void logError(String tag, String message);

    void logWarn(String tag, String message);

    void logInfo(String tag, String message);

    void logDebug(String tag, String message);

    void logVerbose(String tag, String message);

    void logStackTraceWithMessage(String tag, String message, Exception e);

    void logStackTrace(String tag, Exception e);

}
