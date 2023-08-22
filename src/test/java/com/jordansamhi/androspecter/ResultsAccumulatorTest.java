package com.jordansamhi.androspecter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
public class ResultsAccumulatorTest {

    @BeforeEach
    public void setUp() {
        ResultsAccumulator.instance = null; // Resetting the singleton instance for each test
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
}