package io.brodgar.launcher;

/**
 * Which releases the launcher installs. <b>Release</b> takes the highest version among the releases GitHub
 * lists as plain releases; <b>Beta</b> takes the highest among all of them, pre-releases included — so a
 * beta player gets a release too when that is the newest thing. The client's own <code>release.ps1</code>
 * publishes a version with a suffix (<code>0.1.0-beta.1</code>) as a pre-release and a plain one as a release.
 */
enum Channel {
    RELEASE("release", "Release"),
    BETA("beta", "Beta");

    /** The word in <code>launcher.properties</code>. */
    final String key;
    /** The word in the window. */
    final String label;

    Channel(String key, String label) {
        this.key = key;
        this.label = label;
    }

    /** The channel a settings value names; anything unrecognised is <code>def</code>. */
    static Channel of(String key, Channel def) {
        for(Channel c : values()) {
            if(c.key.equalsIgnoreCase(key))
                return c;
        }
        return def;
    }
}
