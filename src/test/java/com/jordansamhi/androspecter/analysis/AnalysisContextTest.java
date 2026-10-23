package com.jordansamhi.androspecter.analysis;

import com.jordansamhi.androspecter.execution.CancellationSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soot.G;

import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class AnalysisContextTest {
    @AfterEach
    void reset() {
        G.reset();
    }

    @Test
    void rejectsUseAfterExternalSootReset() {
        try (AnalysisContext context = AnalysisContext.capture()) {
            context.checkValid();
            G.reset();
            assertFalse(context.isValid());
            assertTrue(assertThrows(IllegalStateException.class, context::checkValid).getMessage().contains("reset"));
        }
    }

    @Test
    void closingCapturedContextDoesNotResetCallerScene() {
        G original = G.v();
        AnalysisContext context = AnalysisContext.capture();
        context.close();
        assertSame(original, G.v());
        assertFalse(context.isValid());
        assertThrows(IllegalStateException.class, context::checkValid);
    }

    @Test
    void detectsCrossThreadUse() throws Exception {
        try (AnalysisContext context = AnalysisContext.capture()) {
            var executor = Executors.newSingleThreadExecutor();
            try {
                assertTrue(executor.submit(() -> {
                    assertFalse(context.isValid());
                    return assertThrows(IllegalStateException.class, context::checkValid)
                            .getMessage().contains("creating thread");
                }).get());
            } finally {
                executor.shutdownNow();
            }
            context.checkValid();
        }
    }

    @Test
    void cancellationStopsFurtherLiveAccessButDoesNotHideLifecycleErrors() {
        try (CancellationSource source = new CancellationSource();
             AnalysisContext context = AnalysisContext.capture(source.token())) {
            source.cancel();
            assertThrows(CancellationException.class, context::checkValid);
            G.reset();
            assertThrows(IllegalStateException.class, context::checkValid);
        }
    }
}
