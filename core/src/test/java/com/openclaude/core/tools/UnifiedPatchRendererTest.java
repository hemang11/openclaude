package com.openclaude.core.tools;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UnifiedPatchRendererTest {
    @Test
    void rendersEditStyleUnifiedPatch() {
        String patch = UnifiedPatchRenderer.render(
                "/tmp/demo.txt",
                "alpha\nbeta\n",
                "alpha\ngamma\n"
        );

        assertTrue(patch.contains("--- /tmp/demo.txt"));
        assertTrue(patch.contains("+++ /tmp/demo.txt"));
        assertTrue(patch.contains("-beta"));
        assertTrue(patch.contains("+gamma"));
    }

    @Test
    void rendersCreateStyleUnifiedPatch() {
        String patch = UnifiedPatchRenderer.render(
                "/tmp/new.txt",
                "",
                "hello\nworld\n"
        );

        assertTrue(patch.contains("@@ -0,0 +1,2 @@"));
        assertTrue(patch.contains("+hello"));
        assertTrue(patch.contains("+world"));
    }
}
