package com.xlythe.sample.camera;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The pillar's shaders compile everywhere, not only on the GPUs that forgive them. */
public class PillarRendererTest {
    /**
     * The words GLSL ES 1.00 reserves for future use (section 3.6). A strict compiler, such as the
     * emulator's, refuses a shader that names anything with one, and the pillar is never drawn.
     */
    private static final Set<String> RESERVED = new HashSet<>(Arrays.asList(
            "asm", "class", "union", "enum", "typedef", "template", "this", "packed", "goto",
            "switch", "default", "inline", "noinline", "volatile", "public", "static", "extern",
            "external", "interface", "flat", "long", "short", "double", "half", "fixed",
            "unsigned", "superp", "input", "output", "hvec2", "hvec3", "hvec4", "dvec2", "dvec3",
            "dvec4", "fvec2", "fvec3", "fvec4", "sampler1D", "sampler3D", "sampler1DShadow",
            "sampler2DShadow", "sampler2DRect", "sampler3DRect", "sampler2DRectShadow", "sizeof",
            "cast", "namespace", "using"));

    /** An identifier in a shader. */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    @Test
    public void theShadersNameNothingWithAReservedWord() {
        assertNoReservedWords("vertex", PillarRenderer.VERTEX_SHADER);
        assertNoReservedWords("fragment", PillarRenderer.FRAGMENT_SHADER);
    }

    private static void assertNoReservedWords(String stage, String source) {
        Set<String> found = new TreeSet<>();
        Matcher identifiers = IDENTIFIER.matcher(source);
        while (identifiers.find()) {
            if (RESERVED.contains(identifiers.group())) {
                found.add(identifiers.group());
            }
        }
        assertTrue("the " + stage + " shader uses reserved words " + found, found.isEmpty());
    }
}
