package com.xlythe.view.camera;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Whether the API makes an app compile against anything this library did not define.
 *
 * <p>The library is built on CameraX, ML Kit, ARCore, media3 and Guava, and an app should be
 * able to use it without knowing that. A type from one of those in a public signature is worse
 * than an implementation detail: it is a dependency the app has to add, a version it has to
 * match, and in the case of the optional ones a class that is not on its classpath at all - so
 * a method that returns one cannot even be called in a build that excluded it.</p>
 *
 * <p>What is allowed is the platform and androidx, because those are what an Android app
 * already compiles against: {@code android.*}, {@code java.*}, {@code javax.*},
 * {@code androidx.*} and this library itself. Everything else is a leak.</p>
 *
 * <h2>Why it reads the source</h2>
 *
 * <p>Reflection was the first attempt and it was not enough. Walking out from the entry points
 * only sees what something public hands back, and the leak this was written for was in a class
 * nothing public mentioned at all - an AR session returning ARCore objects, public because the
 * backend needed to reach it from another package. Nothing would have reached it, so nothing
 * would have caught it. Reading every file finds the class that is public and forgotten, which
 * is the one where a leak survives.</p>
 */
public class ApiSurfaceTypesTest {
    /** What an Android app already has, and so may be named in a signature. */
    private static final List<String> ALLOWED = java.util.Arrays.asList(
            "android.", "androidx.", "java.", "javax.", "com.xlythe.");

    /** A public or protected member, up to the end of its declaration. */
    private static final Pattern MEMBER = Pattern.compile(
            "^\\s*(?:public|protected)\\s+(?!class\\b|interface\\b|enum\\b|@interface\\b)[^;{=]*[;{]");

    /** A type declaration, so a whole class restricted to the library can be skipped. */
    private static final Pattern DECLARATION = Pattern.compile(
            "^\\s*(?:public|protected)\\s+(?:static\\s+|final\\s+|abstract\\s+)*"
                    + "(?:class|interface|enum)\\s+(\\w+)");

    private static final Pattern IMPORT = Pattern.compile(
            "^import\\s+(?:static\\s+)?([\\w.]+);", Pattern.MULTILINE);

    /** A package written out in full, which needs no import and so would slip past one. */
    private static final Pattern INLINE = Pattern.compile("\\b((?:com|org|io)\\.[\\w.]+)\\b");

    @Test
    public void theApiNamesNothingAnAppDoesNotAlreadyHave() throws IOException {
        File source = findSourceRoot();
        Set<String> leaks = new TreeSet<>();
        int filesRead = 0;

        try (Stream<Path> files = Files.walk(source.toPath())) {
            for (Path path : (Iterable<Path>) files.filter(
                    p -> p.toString().endsWith(".java"))::iterator) {
                filesRead++;
                leaks.addAll(leaksIn(path));
            }
        }

        assertTrue("no source was read, so this test was not checking anything", filesRead > 20);
        assertTrue("the API names types an app would have to add a dependency for:\n  "
                        + String.join("\n  ", leaks),
                leaks.isEmpty());
    }

    /**
     * The test can see a leak when there is one.
     *
     * <p>Here because the check is a pattern over text, and a pattern that matches nothing
     * passes every file. This feeds it the shape of the leak it was written for - a public
     * method handing back an ARCore type - and fails if that comes back clean.</p>
     */
    @Test
    public void theCheckCatchesALeakWhenThereIsOne() {
        String leaky = "package com.xlythe.view.camera.ar;\n"
                + "import com.google.ar.core.Frame;\n"
                + "public final class Example {\n"
                + "    public Frame update() { return null; }\n"
                + "}\n";

        Set<String> found = leaksIn("Example.java", leaky);

        assertTrue("a public method returning an ARCore type should be reported, got " + found,
                found.toString().contains("com.google.ar.core.Frame"));
    }

    /** And does not report what is allowed, or what is marked as not being API. */
    @Test
    public void theCheckAllowsThePlatformAndWhatIsRestricted() {
        String platform = "package com.xlythe.view.camera;\n"
                + "import android.media.Image;\n"
                + "import androidx.camera.core.ImageProxy;\n"
                + "public final class Example {\n"
                + "    public Image image() { return null; }\n"
                + "    public ImageProxy proxy() { return null; }\n"
                + "}\n";
        assertTrue("the platform and androidx are what an app already has",
                leaksIn("Example.java", platform).isEmpty());

        String restricted = "package com.xlythe.view.camera.ar;\n"
                + "import androidx.annotation.RestrictTo;\n"
                + "import com.google.ar.core.Frame;\n"
                + "@RestrictTo(RestrictTo.Scope.LIBRARY)\n"
                + "public final class Example {\n"
                + "    public Frame update() { return null; }\n"
                + "}\n";
        assertTrue("a class restricted to the library is not API",
                leaksIn("Example.java", restricted).isEmpty());

        String restrictedMember = "package com.xlythe.view.camera.ar;\n"
                + "import androidx.annotation.RestrictTo;\n"
                + "import com.google.ar.core.Frame;\n"
                + "public final class Example {\n"
                + "    @RestrictTo(RestrictTo.Scope.LIBRARY)\n"
                + "    public Frame update() { return null; }\n"
                + "}\n";
        assertTrue("nor is a restricted member of one that is not",
                leaksIn("Example.java", restrictedMember).isEmpty());
    }

    private static Set<String> leaksIn(Path path) throws IOException {
        return leaksIn(path.getFileName().toString(),
                new String(Files.readAllBytes(path), Charset.forName("UTF-8")));
    }

    private static Set<String> leaksIn(String name, String text) {
        Set<String> leaks = new TreeSet<>();
        Map<String, String> foreign = foreignImports(text);
        String[] lines = text.split("\n", -1);

        for (int i = 0; i < lines.length; i++) {
            if (DECLARATION.matcher(lines[i]).find()) {
                if (isRestricted(lines, i)) {
                    // The whole type is library plumbing, so nothing in it is API.
                    return leaks;
                }
                break;
            }
        }

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!MEMBER.matcher(line).find() || isRestricted(lines, i)) {
                continue;
            }
            for (Map.Entry<String, String> entry : foreign.entrySet()) {
                if (Pattern.compile("\\b" + Pattern.quote(entry.getKey()) + "\\b")
                        .matcher(line).find()) {
                    leaks.add(entry.getValue() + " in " + name + " line " + (i + 1));
                }
            }
            Matcher inline = INLINE.matcher(line);
            while (inline.find()) {
                String named = inline.group(1);
                if (!isAllowed(named)) {
                    leaks.add(named + " in " + name + " line " + (i + 1));
                }
            }
        }
        return leaks;
    }

    /** The imported names an app would have to add a dependency for, by simple name. */
    private static Map<String, String> foreignImports(String text) {
        Map<String, String> foreign = new LinkedHashMap<>();
        Matcher imports = IMPORT.matcher(text);
        while (imports.find()) {
            String full = imports.group(1);
            if (isAllowed(full)) {
                continue;
            }
            foreign.put(full.substring(full.lastIndexOf('.') + 1), full);
        }
        return foreign;
    }

    /**
     * Whether the lines just above are an annotation saying this is not API.
     *
     * <p>A handful of lines, because the annotation sits with the javadoc and the other
     * annotations rather than immediately against the declaration.</p>
     */
    private static boolean isRestricted(String[] lines, int at) {
        for (int i = Math.max(0, at - 6); i < at; i++) {
            if (lines[i].contains("RestrictTo") || lines[i].contains("VisibleForTesting")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAllowed(String name) {
        for (String prefix : ALLOWED) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The library's own source, wherever the test happens to be run from.
     *
     * <p>Fails rather than skips when it cannot be found. A guard that quietly stops guarding is
     * worse than no guard, because the next leak goes in under a green build.</p>
     */
    private static File findSourceRoot() {
        List<String> candidates = new ArrayList<>(java.util.Arrays.asList(
                "src/main/java",
                "camera-view/src/main/java",
                "../camera-view/src/main/java"));
        for (String candidate : candidates) {
            File root = new File(candidate);
            if (root.isDirectory()) {
                return root;
            }
        }
        fail("cannot find the library source from " + new File(".").getAbsolutePath()
                + ", so this test cannot check anything");
        return null;
    }
}
