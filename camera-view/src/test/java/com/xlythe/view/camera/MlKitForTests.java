package com.xlythe.view.camera;

import com.google.mlkit.common.sdkinternal.MlKitContext;

import org.robolectric.RuntimeEnvironment;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Brings ML Kit's context up for a test and takes it down again after.
 *
 * <p>An app gets the context from a manifest provider, which a unit test does not merge. Statics
 * outlive a test class in Robolectric's sandbox, so a context left up would let ML Kit build its
 * clients in a later test that asserts a device without ML Kit is declined.</p>
 */
public final class MlKitForTests {
    private MlKitForTests() {}

    /** Makes the context that building an ML Kit client or an InputImage reads. */
    public static void bringUp() {
        MlKitContext.initializeIfNeeded(RuntimeEnvironment.getApplication());
    }

    /** Forgets the context, leaving ML Kit as an app without its provider has it. */
    public static void takeDown() {
        for (Field field : MlKitContext.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers())
                    && field.getType() == MlKitContext.class) {
                try {
                    field.setAccessible(true);
                    field.set(null, null);
                } catch (IllegalAccessException error) {
                    throw new AssertionError("ML Kit's context could not be taken down", error);
                }
            }
        }
    }
}
