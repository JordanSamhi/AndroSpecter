package com.jordansamhi.androspecter.instrumentation;

import com.jordansamhi.androspecter.analysis.AnalysisContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soot.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TransformationPipelineTest {
    @BeforeEach void reset() { G.reset(); }
    @AfterEach void cleanup() { G.reset(); }

    @Test
    void stagesSeePreviousChangesAndBuiltPipelineKeepsItsOwnOrder() {
        SootClass subject = new SootClass("example.Subject", Modifier.PUBLIC);
        Scene.v().addClass(subject);
        List<String> events = new ArrayList<>();
        TransformationPipeline.Builder builder = TransformationPipeline.builder()
                .add(context -> {
                    subject.addField(new SootField("added", IntType.v(), Modifier.PUBLIC));
                    events.add("first");
                })
                .add(context -> {
                    assertTrue(subject.declaresFieldByName("added"));
                    events.add("second");
                });
        TransformationPipeline pipeline = builder.build();
        builder.add(context -> events.add("not in the built pipeline"));
        try (AnalysisContext context = AnalysisContext.capture()) {
            pipeline.apply(context);
        }
        assertEquals(List.of("first", "second"), events);
    }

    @Test
    void invalidationDuringOneStagePreventsFollowingStage() {
        AtomicInteger visits = new AtomicInteger();
        TransformationPipeline pipeline = TransformationPipeline.builder().add(context -> G.reset())
                .add(context -> visits.incrementAndGet()).build();
        try (AnalysisContext context = AnalysisContext.capture()) {
            assertThrows(IllegalStateException.class, () -> pipeline.apply(context));
        }
        assertEquals(0, visits.get());
    }

    @Test
    void closedContextRejectsEvenAnEmptyPipeline() {
        AnalysisContext context = AnalysisContext.capture();
        context.close();
        assertThrows(IllegalStateException.class, () -> TransformationPipeline.builder().build().apply(context));
    }
}
