package com.xlythe.view.camera.ar;

/**
 * Why the world is not being tracked, when it is not.
 *
 * <p>Worth reporting rather than swallowing, because most of these are things the person
 * holding the phone can fix and none of them are things the app can. A session that will not
 * track in a dark room looks exactly like a broken one from the outside, and the difference is
 * a sentence on screen asking them to turn a light on.</p>
 *
 * <p>Its own enum rather than ARCore's, so that an app can read it without compiling against
 * ARCore - the same reason {@link ArFrame} is an interface.</p>
 */
public enum ArTrackingFailure {
    /**
     * Nothing is wrong; the session has not finished working out where it is.
     *
     * <p>What the first second or two reports, and what a session reports while it is still
     * gathering the movement it needs. Not worth showing anyone.</p>
     */
    STARTING,

    /** Too dark to see anything to track. Turn a light on. */
    INSUFFICIENT_LIGHT,

    /** The camera is seeing a blank wall or a flat surface, with nothing to fix on. */
    INSUFFICIENT_FEATURES,

    /** The device is being moved too fast to follow. Slow down. */
    EXCESSIVE_MOTION,

    /** The camera went away, usually because another app took it. */
    CAMERA_UNAVAILABLE,

    /** The session is in a state it cannot track from, which is not something to wait out. */
    BAD_STATE,

    /** A reason this library does not know about, from a newer ARCore. */
    UNKNOWN,
}
