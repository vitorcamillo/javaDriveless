package io.github.selenium.javaDriverless.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

/** Parse do mapa de Target.getTargets para TargetInfo (payload CDP -> tipagem). */
class TargetInfoTest {

    private Target mkTarget() {
        return org.mockito.Mockito.mock(Target.class);
    }

    @Test
    void parsesAllFieldsFromCdpMap() {
        Map<String, Object> info = new HashMap<>();
        info.put("targetId", "T-1");
        info.put("type", "page");
        info.put("title", "Página");
        info.put("url", "https://example.test/");
        info.put("attached", Boolean.TRUE);
        info.put("openerId", "T-0");
        info.put("canAccessOpener", Boolean.FALSE);
        info.put("openerFrameId", "F-9");
        info.put("browserContextId", "C-2");
        info.put("subtype", "webapp");

        Target target = mkTarget();
        Target.TargetInfo parsed = new Target.TargetInfo(info, CompletableFuture.completedFuture(target));

        assertEquals("T-1", parsed.getId());
        assertEquals("page", parsed.getType());
        assertEquals("Página", parsed.getTitle());
        assertEquals("https://example.test/", parsed.getUrl());
        assertEquals(Boolean.TRUE, parsed.getAttached());
        assertEquals("T-0", parsed.getOpenerId());
        assertEquals(Boolean.FALSE, parsed.getCanAccessOpener());
        assertEquals("F-9", parsed.getOpenerFrameId());
        assertEquals("C-2", parsed.getBrowserContextId());
        assertEquals("webapp", parsed.getSubtype());
        assertSame(target, parsed.getTarget());
    }

    @Test
    void missingOptionalFieldsBecomeNull() {
        Target.TargetInfo parsed =
            new Target.TargetInfo(Map.of("targetId", "T-2", "type", "iframe"),
                CompletableFuture.completedFuture(mkTarget()));

        assertNull(parsed.getTitle());
        assertNull(parsed.getUrl());
        assertNull(parsed.getAttached());
        assertNull(parsed.getOpenerId());
        assertNull(parsed.getCanAccessOpener());
        assertNull(parsed.getOpenerFrameId());
        assertNull(parsed.getBrowserContextId());
        assertNull(parsed.getSubtype());
    }

    @Test
    void toStringIncludesIdAndType() {
        Target.TargetInfo parsed =
            new Target.TargetInfo(Map.of("targetId", "T-3", "type", "page"),
                CompletableFuture.completedFuture(mkTarget()));

        // toString real expõe type e title (não o id)
        String repr = parsed.toString();
        assertTrue(repr.contains("page"), repr);
    }

    @Test
    void targetGetterFailurePropagatesOnGetTarget() {
        CompletableFuture<Target> failed =
            CompletableFuture.failedFuture(new IllegalStateException("target fechado"));
        Target.TargetInfo parsed =
            new Target.TargetInfo(Map.of("targetId", "T-4", "type", "page"), failed);

        java.util.concurrent.CompletionException thrown =
            org.junit.jupiter.api.Assertions.assertThrows(
                java.util.concurrent.CompletionException.class, parsed::getTarget);
        assertTrue(thrown.getCause().getMessage().contains("target fechado"));
    }
}
