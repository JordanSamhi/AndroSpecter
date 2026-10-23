package com.jordansamhi.androspecter.printers;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WriterTest {
    @Test
    void forwardsUnmodifiedMessagesAtTheAppropriateLoggingLevel() {
        List<String> messages = new ArrayList<>();
        Logger logger = (Logger) Proxy.newProxyInstance(Logger.class.getClassLoader(), new Class<?>[]{Logger.class},
                (proxy, method, args) -> {
                    if (Arrays.asList("error", "warn", "info").contains(method.getName())) {
                        messages.add(method.getName() + ":" + args[0]);
                    }
                    if (method.getReturnType() == boolean.class) {
                        return true;
                    }
                    return null;
                });
        Writer writer = new Writer(logger);
        writer.perror("Analysis failed");
        writer.pwarning("Partial result");
        writer.psuccess("Analysis complete");
        writer.pinfo("Inspecting APK");

        assertEquals(Arrays.asList("error:Analysis failed", "warn:Partial result",
                "info:Analysis complete", "info:Inspecting APK"), messages);
    }
}
