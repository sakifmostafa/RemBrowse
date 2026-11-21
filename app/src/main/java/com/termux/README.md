# Terminal emulator (from Termux)

The `com.termux.terminal` and `com.termux.view` packages here are the terminal emulator and its
renderer from [Termux](https://github.com/termux/termux-app) (`terminal-emulator` and
`terminal-view`, master branch as of November 2026), which in turn derive from Jack Palevich's
[Terminal Emulator for Android](https://github.com/jackpal/Android-Terminal-Emulator).

They are licensed under the Apache License, Version 2.0 (see [`LICENSE`](LICENSE)); the rest of
the Termux app is GPLv3 and none of it is included.

What is included:

- `terminal/`: `TerminalEmulator`, `TerminalBuffer`, `TerminalRow`, `TextStyle`, `TerminalColors`,
  `TerminalColorScheme`, `KeyHandler`, `WcWidth`, `TerminalOutput`, `Logger`, and the inline image
  support (`TerminalBitmap`, `TerminalSixel`, `ITermImage`, `AndroidUtils`), unchanged, and
  `TerminalSessionClient`, trimmed (see the note at its top).
- `view/TerminalRenderer.java`, with a public accessor for one font metric added (see its top).

Not included: Termux's `TerminalSession` (a local process behind a pseudo-terminal, with native
code), `TerminalView` and the rest of `terminal-view`. RemBrowse's terminals talk to SSH servers;
its own view is `com.rembrowser.rembrowse.terminal.TerminalScreenView`.
