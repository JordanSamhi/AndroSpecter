package com.jordansamhi.androspecter.printers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WriterTest {

    private Writer writer;
    private final ByteArrayOutputStream outputStreamCaptor = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() {
        writer = Writer.v();
        System.setOut(new PrintStream(outputStreamCaptor));
    }

    @Test
    void testPerror() {
        writer.perror("An error occurred");
        assertEquals("[x] An error occurred\n", outputStreamCaptor.toString());
    }

    @Test
    void testPsuccess() {
        writer.psuccess("Operation successful");
        assertEquals("[✓] Operation successful\n", outputStreamCaptor.toString());
    }

    @Test
    void testPwarning() {
        writer.pwarning("This is a warning");
        assertEquals("[!] This is a warning\n", outputStreamCaptor.toString());
    }

    @Test
    void testPinfo() {
        writer.pinfo("Some info");
        assertEquals("[*] Some info\n", outputStreamCaptor.toString());
    }
}
