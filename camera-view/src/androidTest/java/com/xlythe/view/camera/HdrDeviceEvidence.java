package com.xlythe.view.camera;

import android.app.UiAutomation;
import android.graphics.Bitmap;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a device run leaves behind about HDR: the compositor's account of this app's layers,
 * screenshots, and the camera's streams.
 *
 * <p>A screenshot cannot show HDR brightness, so the compositor's dump is the evidence: the
 * dataspace of each layer's buffers, and how far above SDR white it is asked to go.</p>
 */
public final class HdrDeviceEvidence {
    /** The package every layer of this app is named after. */
    private static final String PACKAGE = "com.xlythe.view.camera.test";

    private HdrDeviceEvidence() {}

    /**
     * Logs the compositor's lines about this app's layers - each layer's own block, its row in
     * the composition table - and the dataspace of each layer it is composing now, and returns
     * the whole dump for {@link #showsHdr}.
     */
    @NonNull
    public static String compositor(@NonNull String tag, @NonNull String label)
            throws IOException {
        String dump = shell("dumpsys SurfaceFlinger");
        String[] lines = dump.split("\n");
        StringBuilder ours = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!line.contains(PACKAGE)) {
                continue;
            }
            boolean header = line.trim().startsWith("* Layer") || line.trim().startsWith("+ ");
            int end = header ? Math.min(lines.length, i + 30) : i + 1;
            for (int j = i; j < end; j++) {
                ours.append(lines[j]).append('\n');
            }
            i = end - 1;
        }
        for (String line : ours.toString().split("\n")) {
            Log.i(tag, label + " sf: " + line);
        }
        for (Map.Entry<String, String> layer : composedDataspaces(dump).entrySet()) {
            Log.i(tag, label + " composed: " + layer.getKey() + " dataspace=" + layer.getValue());
        }
        return dump;
    }

    /**
     * Whether the compositor, in a dump from {@link #compositor}, is composing a layer of this
     * app's whose buffers are BT.2020 HLG or PQ.
     *
     * <p>Only layers in the composition table count. A layer that has left the window can stay
     * listed, buffer and all, until whatever wrote into it lets go of it, but it is not drawn.</p>
     */
    public static boolean showsHdr(@NonNull String dump) {
        for (String dataspace : composedDataspaces(dump).values()) {
            if (dataspace.contains("HLG") || dataspace.contains("ST2084")
                    || dataspace.contains("_PQ")) {
                return true;
            }
        }
        return false;
    }

    /**
     * The layers of this app's that the compositor is composing now, each with the dataspace of
     * its buffers, or "none" for a layer without one.
     */
    @NonNull
    static Map<String, String> composedDataspaces(@NonNull String dump) {
        String[] lines = dump.split("\n");
        Map<String, String> composed = new LinkedHashMap<>();
        for (String line : lines) {
            int bar = line.lastIndexOf(" | ");
            if (bar < 0 || !line.contains(PACKAGE)) {
                continue;
            }
            String name = line.substring(bar + 3).trim();
            if (name.contains(PACKAGE)) {
                composed.put(name, "none");
            }
        }
        for (Map.Entry<String, String> layer : composed.entrySet()) {
            String header = "+ LayerFE (" + layer.getKey() + ")";
            for (int i = 0; i < lines.length; i++) {
                if (!lines[i].trim().equals(header)) {
                    continue;
                }
                for (int j = i + 1; j < lines.length && !lines[j].trim().startsWith("+ "); j++) {
                    int at = lines[j].indexOf("dataspace=");
                    if (at >= 0) {
                        int space = lines[j].indexOf(' ', at);
                        layer.setValue(lines[j].substring(at + "dataspace=".length(),
                                space < 0 ? lines[j].length() : space));
                        break;
                    }
                }
                break;
            }
        }
        return composed;
    }

    /** Logs the camera service's account of the streams it is running. */
    public static void cameraStreams(@NonNull String tag, @NonNull String label)
            throws IOException {
        for (String line : shell("dumpsys media.camera").split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Stream[") || trimmed.toLowerCase().contains("dynamic range")
                    || trimmed.startsWith("Format") || trimmed.contains("Dataspace")
                    || trimmed.contains("Consumer name") || trimmed.contains("Client package")) {
                Log.i(tag, label + " camera: " + trimmed);
            }
        }
    }

    /** Saves a screenshot under the test's external files, where the run script collects it. */
    public static void screenshot(@NonNull String label) throws IOException {
        UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        Bitmap bitmap = automation.takeScreenshot();
        if (bitmap == null) {
            return;
        }
        File directory = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getExternalFilesDir(null);
        try (FileOutputStream out = new FileOutputStream(new File(directory, label + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }

    /** Runs a shell command as the shell user, returning what it printed. */
    @NonNull
    public static String shell(@NonNull String command) throws IOException {
        UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        ParcelFileDescriptor output = automation.executeShellCommand(command);
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(output.getFileDescriptor()), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                text.append(line).append('\n');
            }
        } finally {
            output.close();
        }
        return text.toString();
    }

    /** Copies a file the shell can read, such as one pushed to /data/local/tmp, to a file. */
    public static boolean copyFromShell(@NonNull String path, @NonNull File into)
            throws IOException {
        UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        ParcelFileDescriptor output = automation.executeShellCommand("cat " + path);
        long copied = 0;
        try (FileInputStream in = new FileInputStream(output.getFileDescriptor());
                FileOutputStream out = new FileOutputStream(into)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                copied += read;
            }
        } finally {
            output.close();
        }
        return copied > 0;
    }
}
