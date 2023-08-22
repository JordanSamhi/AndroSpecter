package com.jordansamhi.androspecter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TimeOutTest {

    @Test
    public void testConstructorWithPositiveValue() {
        TimeOut timeout = new TimeOut(120);
        assertEquals(120, timeout.getTimeout());
    }

    @Test
    public void testConstructorWithZeroValue() {
        TimeOut timeout = new TimeOut(0);
        assertEquals(60, timeout.getTimeout()); // Default timeout is 60 seconds
    }

    @Test
    public void testCancel() {
        TimeOut timeout = new TimeOut(120);
        timeout.launch();
        timeout.cancel(); // Test to ensure no exceptions are thrown
    }
}
