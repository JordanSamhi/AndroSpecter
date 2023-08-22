package com.jordansamhi.androspecter;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;


class MethodSignatureConverterTest {

    @Test
    void testGetClassNameFromJimpleSignature() {
        String jimpleSignature = "<com.example.MyClass void myMethod(int,double)>";
        assertEquals("com.example.MyClass", MethodSignatureConverter.v().getClassNameFromJimpleSignature(jimpleSignature));
    }

    @Test
    void testGetMethodNameFromJimpleSignature() {
        String jimpleSignature = "<com.example.MyClass void myMethod(int,double)>";
        assertEquals("myMethod", MethodSignatureConverter.v().getMethodNameFromJimpleSignature(jimpleSignature));
    }

    @Test
    void testGetReturnNameFromJimpleSignature() {
        String jimpleSignature = "<com.example.MyClass void myMethod(int,double)>";
        assertEquals("void", MethodSignatureConverter.v().getReturnNameFromJimpleSignature(jimpleSignature));
    }

    @Test
    void testGetParametersNamesFromJimpleSignature() {
        String jimpleSignature = "<com.example.MyClass void myMethod(int,double)>";
        List<String> expectedParameters = Arrays.asList("int", "double");
        assertEquals(expectedParameters, MethodSignatureConverter.v().getParametersNamesFromJimpleSignature(jimpleSignature));
    }

    @Test
    void testJavaSigToSootSig() {
        String javaSignature = "Lcom/example/Class;";
        assertEquals("com.example.Class", MethodSignatureConverter.v().javaSigToSootSig(javaSignature));
    }

    @Test
    void testSigToSubSig() {
        String sig = "<com.example.MyClass void myMethod(int,double)>";
        assertEquals("void myMethod(int,double)", MethodSignatureConverter.v().sigToSubSig(sig));
    }
}