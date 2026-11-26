package com.xlythe.view.camera.caption;

import android.content.Context;
import android.icu.util.ULocale;
import android.os.CancellationSignal;
import android.util.Log;
import android.util.SparseArray;
import android.view.translation.TranslationCapability;
import android.view.translation.TranslationContext;
import android.view.translation.TranslationManager;
import android.view.translation.TranslationRequest;
import android.view.translation.TranslationRequestValue;
import android.view.translation.TranslationResponse;
import android.view.translation.TranslationResponseValue;
import android.view.translation.TranslationSpec;
import android.view.translation.Translator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.RestrictTo;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import static com.xlythe.view.camera.ICameraModule.TAG;

/**
 * Translating a line, using the translator the platform already has.
 *
 * <p>The platform one rather than a library, and that is the whole point of this class being
 * short. Android 12 added an on-device translator for translating what is on screen, and it
 * translates text for anyone who asks - so captions can be translated with nothing added to
 * this library, no model to ship, no dependency for an app to match a version of, and no
 * network. The alternative was ML Kit, which is a dependency and a download.</p>
 *
 * <p>Whether a pair of languages can be translated is the device's answer, not ours: the
 * service downloads languages on demand and reports which it has. A pair it does not have is
 * reported as unavailable rather than waited for, because a caption that arrives after the
 * conversation has moved on is no use.</p>
 *
 * @hide
 */
@RequiresApi(31)
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class LiveTranslation {
    /** Low latency, because these are captions: a late translation is a wrong one. */
    private static final int FLAGS = TranslationContext.FLAG_LOW_LATENCY;

    private final Executor mExecutor;

    @Nullable
    private Translator mTranslator;

    /** The request in flight for a line that is still being spoken, so it can be dropped. */
    @Nullable
    private CancellationSignal mPending;

    private boolean mClosed;

    private LiveTranslation(@NonNull Executor executor) {
        mExecutor = executor;
    }

    /** Told whether a translator could be made, and given it if so. */
    public interface Ready {
        void onTranslator(@Nullable LiveTranslation translation);
    }

    /**
     * Asks the platform for a translator between two languages.
     *
     * <p>Asynchronous because creating one is: the service may have to load a language. The
     * callback is given null when it cannot, which is a device without a translation service,
     * a pair of languages it does not have, or a service that simply refused.</p>
     */
    public static void create(@NonNull Context context, @NonNull Locale from, @NonNull Locale to,
            @NonNull Executor executor, @NonNull Ready ready) {
        TranslationManager manager = context.getSystemService(TranslationManager.class);
        if (manager == null) {
            // No translation service on this device, which is a thing an OEM may leave out.
            ready.onTranslator(null);
            return;
        }
        TranslationContext translationContext;
        try {
            translationContext = new TranslationContext.Builder(
                    new TranslationSpec(ULocale.forLocale(from), TranslationSpec.DATA_FORMAT_TEXT),
                    new TranslationSpec(ULocale.forLocale(to), TranslationSpec.DATA_FORMAT_TEXT))
                    .setTranslationFlags(FLAGS)
                    .build();
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to describe a translation from " + from + " to " + to, error);
            ready.onTranslator(null);
            return;
        }
        LiveTranslation translation = new LiveTranslation(executor);
        try {
            manager.createOnDeviceTranslator(translationContext, executor, translator -> {
                if (translator == null) {
                    Log.i(TAG, "This device will not translate " + from + " to " + to);
                    ready.onTranslator(null);
                    return;
                }
                translation.mTranslator = translator;
                ready.onTranslator(translation);
            });
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to ask for a translator", error);
            ready.onTranslator(null);
        }
    }

    /**
     * Whether the device can translate between two languages without downloading one first.
     *
     * <p>Blocks, and the platform says so - the capability query takes seconds - so it belongs
     * on a worker thread. A pair that is only available to download counts as no: the download
     * is the service's business and not something to hold a conversation up for.</p>
     */
    @NonNull
    public static Availability availabilityOf(@NonNull Context context, @NonNull Locale from,
            @NonNull Locale to) {
        TranslationManager manager = context.getSystemService(TranslationManager.class);
        if (manager == null) {
            return Availability.NO_SERVICE;
        }
        Set<TranslationCapability> capabilities;
        try {
            capabilities = manager.getOnDeviceTranslationCapabilities(
                    TranslationSpec.DATA_FORMAT_TEXT, TranslationSpec.DATA_FORMAT_TEXT);
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to ask what this device can translate", error);
            return Availability.NO_SERVICE;
        }
        if (capabilities == null) {
            capabilities = Collections.emptySet();
        }
        Availability best = Availability.UNSUPPORTED;
        for (TranslationCapability capability : capabilities) {
            if (!matches(capability.getSourceSpec(), from)
                    || !matches(capability.getTargetSpec(), to)) {
                continue;
            }
            switch (capability.getState()) {
                case TranslationCapability.STATE_ON_DEVICE:
                    return Availability.READY;
                case TranslationCapability.STATE_DOWNLOADING:
                case TranslationCapability.STATE_AVAILABLE_TO_DOWNLOAD:
                    best = Availability.NEEDS_DOWNLOAD;
                    break;
                default:
                    break;
            }
        }
        return best;
    }

    /**
     * Where the user can get the languages, or null if there is nowhere to send them.
     *
     * <p>Worth having because a translation that is only available to download is a dead end
     * otherwise: the service will not fetch a language because an app asked, and the app cannot
     * fetch it either. This is the screen the user does it from.</p>
     */
    @Nullable
    public static android.app.PendingIntent settingsIntent(@NonNull Context context) {
        TranslationManager manager = context.getSystemService(TranslationManager.class);
        if (manager == null) {
            return null;
        }
        try {
            return manager.getOnDeviceTranslationSettingsActivityIntent();
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to ask where translation languages are managed", error);
            return null;
        }
    }

    /** What the device can do about a pair of languages. */
    public enum Availability {
        /** The languages are on the device and translation will work now. */
        READY,
        /** The service has them but has not downloaded them, so not yet. */
        NEEDS_DOWNLOAD,
        /** The service does not have this pair at all. */
        UNSUPPORTED,
        /** There is no translation service on this device. */
        NO_SERVICE,
    }

    /** Whether a capability's language is the one asked for, compared by language alone. */
    private static boolean matches(@Nullable TranslationSpec spec, @NonNull Locale locale) {
        if (spec == null) {
            return false;
        }
        // By language rather than by the whole tag: a service that offers "en" can translate
        // "en-GB", and comparing tags would report a pair as unsupported over a region nobody
        // asked about.
        return spec.getLocale().getLanguage().equalsIgnoreCase(locale.getLanguage());
    }

    /**
     * Translates one line, reporting the result or nothing.
     *
     * @param replacesPending true for a line that is still being spoken, which drops whatever
     *     was in flight for the previous version of it - the words have changed, so the
     *     translation that was being made is already out of date
     */
    public void translate(@NonNull String text, boolean replacesPending,
            @NonNull Consumer<String> onTranslated) {
        Translator translator = mTranslator;
        if (mClosed || translator == null || text.trim().isEmpty()) {
            return;
        }
        if (replacesPending) {
            cancelPending();
        }
        CancellationSignal signal = new CancellationSignal();
        if (replacesPending) {
            mPending = signal;
        }
        TranslationRequest request = new TranslationRequest.Builder()
                .setTranslationRequestValues(
                        Collections.singletonList(TranslationRequestValue.forText(text)))
                .build();
        try {
            translator.translate(request, signal, mExecutor, response -> {
                if (mPending == signal) {
                    mPending = null;
                }
                String translated = read(response);
                if (translated != null && !mClosed) {
                    onTranslated.accept(translated);
                }
            });
        } catch (IllegalStateException destroyed) {
            // The session went away underneath us, which is what this throws for.
            Log.d(TAG, "The translator was already closed");
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to translate a caption", error);
        }
    }

    /** The translated text, or null when the service could not translate this line. */
    @Nullable
    private static String read(@Nullable TranslationResponse response) {
        if (response == null) {
            return null;
        }
        SparseArray<TranslationResponseValue> values = response.getTranslationResponseValues();
        if (values == null || values.size() == 0) {
            return null;
        }
        TranslationResponseValue value = values.valueAt(0);
        if (value == null || value.getStatusCode() != TranslationResponseValue.STATUS_SUCCESS) {
            // A line the service declined, which is reported as no translation rather than as a
            // failure: the next line usually translates.
            return null;
        }
        CharSequence text = value.getText();
        return text == null ? null : text.toString();
    }

    private void cancelPending() {
        CancellationSignal pending = mPending;
        mPending = null;
        if (pending != null) {
            pending.cancel();
        }
    }

    /** Gives the translator back. Nothing is translated afterwards. */
    public void close() {
        mClosed = true;
        cancelPending();
        Translator translator = mTranslator;
        mTranslator = null;
        if (translator != null) {
            try {
                translator.destroy();
            } catch (RuntimeException error) {
                Log.w(TAG, "Unable to release the translator", error);
            }
        }
    }
}
