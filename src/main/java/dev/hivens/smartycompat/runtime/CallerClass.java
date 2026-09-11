package dev.hivens.smartycompat.runtime;

/**
 * A stand-in for the two {@code sun.reflect.Reflection.getCallerClass}
 * methods, counting stack frames the way the JDK 8 originals did.
 *
 * Those methods are gone from the JDK, so a mod that calls them cannot run
 * on a modern JVM unless something redirects the call. Cleanroom's Fugue
 * does redirect it, by swapping the owner and keeping the name and the
 * descriptor, but its target counts frames one closer than the original:
 * where the JDK's {@code getCallerClass(2)} answered "my caller's caller",
 * that one answers "my caller's caller's caller". A mod passing the depth
 * the old API wanted therefore receives the wrong class.
 *
 * The numbering here is the JDK's. Depth 0 is the method being called, 1 is
 * whoever called it, 2 is that method's caller, and so on.
 *
 * Resolution goes through this class's own loader and asks for the class
 * without initialising it. That loader is the one the game runs mods on:
 * the coremod excludes this package from transformation, which skips the
 * transformer chain but still loads through the game's loader rather than
 * delegating to the parent, so a mod class named by a frame resolves. The
 * frame being named is by definition already executing, so this hands back
 * the loaded class rather than starting anything.
 */
public final class CallerClass {

    /**
     * Frames between the capture and the public method that was called:
     * the capture happens one level down in {@link #at(int)}, so every
     * depth the caller passes has to step over that.
     */
    private static final int CAPTURE_OFFSET = 1;

    private CallerClass() {
    }

    /**
     * The class of the caller of the method that called this one.
     *
     * Follows what the no-argument original documented rather than what it
     * was measured to do: the JDK refused that overload from any method not
     * annotated CallerSensitive, which no mod is, so there is nothing to
     * compare against. The depth-taking overload below is the one that was
     * checked frame for frame, and the one anything here rewrites.
     */
    public static Class<?> getCallerClass() {
        return at(2);
    }

    /**
     * The class {@code depth} frames up, matching the original's numbering:
     * 1 is the direct caller, 2 is its caller.
     */
    public static Class<?> getCallerClass(int depth) {
        return at(depth);
    }

    private static Class<?> at(int depth) {
        StackTraceElement[] frames = new Throwable().getStackTrace();
        int index = depth + CAPTURE_OFFSET;
        if (depth < 0 || index >= frames.length) {
            throw new IllegalStateException(
                "no caller at depth " + depth + ", the stack is " + frames.length + " frames deep");
        }

        String name = frames[index].getClassName();
        try {
            return Class.forName(name, false, CallerClass.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("caller " + name + " is not loadable here", e);
        }
    }
}
