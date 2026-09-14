The launcher updates itself.

- On start it looks for a newer release of itself on the channel you pick, downloads it with a progress bar, and starts again as the new one. The game is not touched.
- The Java runtime it brings along is swapped in by `run.bat` the next time it starts the launcher while the game is not running.
- **Options**: the update checkbox now covers the launcher and the game both.
