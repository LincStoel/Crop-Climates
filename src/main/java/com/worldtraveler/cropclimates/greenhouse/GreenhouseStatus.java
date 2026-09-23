package com.worldtraveler.cropclimates.greenhouse;

/** What a hygrometer currently reports - synced to clients for Jade and the dial. */
public enum GreenhouseStatus {
    /** Not scanned yet, or waiting on unloaded chunks. Shows outdoor humidity meanwhile. */
    SCANNING,
    /** In a sealed room: shows the room's humidity. */
    GREENHOUSE,
    /** Open to the sky: shows outdoor humidity. */
    OUTDOOR,
    /** Sealed, but smaller than the greenhouse minimum: shows outdoor humidity. */
    TOO_SMALL,
    /** Sealed, but larger than the greenhouse cap: shows outdoor humidity. */
    TOO_LARGE,
    /** Greenhouses are turned off in the server config. */
    DISABLED;

    private static final GreenhouseStatus[] VALUES = values();

    public static GreenhouseStatus byId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : SCANNING;
    }
}
