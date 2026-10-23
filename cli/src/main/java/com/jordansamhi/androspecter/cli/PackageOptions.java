package com.jordansamhi.androspecter.cli;

import picocli.CommandLine.Option;
import java.util.ArrayList;
import java.util.List;

final class PackageOptions {
    @Option(names = "--package", paramLabel = "PREFIX", description = "Select a package and subpackages; repeat to combine scopes.") List<String> prefixes = new ArrayList<>();

    void validate(CliSupport command) {
        for (int index = 0; index < prefixes.size(); index++) {
            String value = prefixes.get(index).strip();
            if (value.endsWith(".")) value = value.substring(0, value.length() - 1);
            if (value.isBlank() || value.startsWith(".") || value.contains("..")) {
                throw command.usage("--package must contain a nonempty package prefix");
            }
            prefixes.set(index, value + ".");
        }
    }

    boolean matchesClass(String name) {
        return prefixes.isEmpty() || prefixes.stream().anyMatch(name::startsWith);
    }

    boolean matchesMethod(String signature) {
        if (prefixes.isEmpty()) return true;
        int colon = signature.indexOf(':');
        return signature.startsWith("<") && colon > 1 && matchesClass(signature.substring(1, colon));
    }
}
