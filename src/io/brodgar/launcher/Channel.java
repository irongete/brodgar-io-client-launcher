package io.brodgar.launcher;

/**
 * Release filter. <code>RELEASE</code>: highest semver tag with <code>prerelease=false</code>;
 * <code>BETA</code>: highest tag of all. The client's <code>release.ps1</code> publishes a suffixed version
 * (<code>0.1.0-beta.1</code>) as prerelease, a plain one as release.
 */
enum Channel {
    RELEASE("release", "Release"),
    BETA("beta", "Beta");

    /** Value of <code>channel</code> in <code>launcher.properties</code>. */
    final String key;
    /** Dropdown text. */
    final String label;

    Channel(String key, String label) {
        this.key = key;
        this.label = label;
    }

    /** Channel with <code>key</code> (case-insensitive), or <code>def</code>. */
    static Channel of(String key, Channel def) {
        for(Channel c : values()) {
            if(c.key.equalsIgnoreCase(key))
                return c;
        }
        return def;
    }
}
