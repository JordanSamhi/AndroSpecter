package com.jordansamhi.androspecter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ResultsAccumulatorTest {

    @Test
    void instanceMetricsAndSnapshotsDoNotShareMutableState() {
        ResultsAccumulator first = new ResultsAccumulator();
        ResultsAccumulator second = new ResultsAccumulator();
        first.setAppName("first");
        first.setMetric("calls", 4);
        var snapshot = first.snapshot();
        first.incrementMetric("calls");
        first.reset();
        assertEquals(4, snapshot.metrics().get("calls"));
        assertEquals("first", snapshot.appName());
        assertEquals(0, second.getMetric("calls"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.metrics().put("calls", 9));
    }

    @BeforeEach
    @AfterEach
    void resetAccumulator() {
        ResultsAccumulator.v().reset();
    }

    @Test
    public void testSingletonInstance() {
        ResultsAccumulator instance1 = ResultsAccumulator.v();
        ResultsAccumulator instance2 = ResultsAccumulator.v();
        assertSame(instance1, instance2);
    }

    @Test
    public void testAppName() {
        ResultsAccumulator ra = ResultsAccumulator.v();
        ra.setAppName("TestApp");
        assertEquals("TestApp", ra.getAppName());
    }

    @Test
    public void testIncrementMetric() {
        ResultsAccumulator ra = ResultsAccumulator.v();
        ra.incrementMetric("metric1");
        ra.incrementMetric("metric1");
        assertEquals(2, ra.getMetric("metric1"));
    }

    @Test
    public void testSetMetric() {
        ResultsAccumulator ra = ResultsAccumulator.v();
        ra.setMetric("metric1", 10);
        assertEquals(10, ra.getMetric("metric1"));
    }

    @Test
    public void testGetMetricNonExistent() {
        ResultsAccumulator ra = ResultsAccumulator.v();
        assertEquals(0, ra.getMetric("nonExistentMetric"));
    }

    @Test
    public void testGetVectorResults() {
        ResultsAccumulator ra = ResultsAccumulator.v();
        ra.setAppName("TestApp");
        ra.setMetric("metric1", 10);
        ra.setMetric("metric2", 20);
        String expected = "TestApp,10,20";
        assertEquals(expected, ra.getVectorResults());
    }

    @Test
    void metricColumnsDoNotDependOnInsertionOrder() {
        ResultsAccumulator ra = ResultsAccumulator.v();
        ra.setAppName("app");
        ra.setMetric("zeta", 2);
        ra.setMetric("alpha", 1);
        String firstRun = ra.getVectorResults();
        ra.reset();
        ra.setAppName("app");
        ra.setMetric("alpha", 1);
        ra.setMetric("zeta", 2);
        assertEquals("app,1,2", firstRun);
        assertEquals(firstRun, ra.getVectorResults());
    }

    @Test
    void resetRemovesPreviousApplicationsResults() {
        ResultsAccumulator ra = ResultsAccumulator.v();
        ra.setAppName("old-app");
        ra.incrementMetric("old-metric");
        ra.reset();
        assertEquals("", ra.getAppName());
        assertEquals(0, ra.getMetric("old-metric"));
        assertEquals("", ra.getVectorResults());
    }

    @Test
    void concurrentIncrementsAreNotLost() {
        ResultsAccumulator ra = ResultsAccumulator.v();
        IntStream.range(0, 10000).parallel().forEach(i -> ra.incrementMetric("events"));
        assertEquals(10000, ra.getMetric("events"));
    }

    @Test
    void vectorEscapesApplicationNamesAsCsv() {
        ResultsAccumulator ra = ResultsAccumulator.v();
        ra.setAppName("an, \"app\"\nname");
        ra.setMetric("count", 3);
        assertEquals("\"an, \"\"app\"\"\nname\",3", ra.getVectorResults());
    }
}
