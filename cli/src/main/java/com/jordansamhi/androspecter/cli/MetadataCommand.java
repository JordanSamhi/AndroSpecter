package com.jordansamhi.androspecter.cli;

import com.jordansamhi.androspecter.metadata.ApkInfo;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "metadata", description = "Read manifest metadata without an Android SDK or Soot session.")
final class MetadataCommand extends CliSupport implements Callable<Integer> {
    @Option(names = "--apk", required = true, paramLabel = "FILE", description = "APK to inspect.") Path apk;
    @Option(names = "--format", defaultValue = "JSON", description = "Output format: ${COMPLETION-CANDIDATES}.") ReportFormat format;
    @Mixin OutputOptions output;

    /**
     * Reads and emits detached manifest declarations.
     * @return success status
     * @throws Exception if reading/writing fails
     */
    @Override public Integer call() throws Exception {
        requireFile(apk, "APK");
        output.validate(this, List.of(apk));
        ApkInfo info = ApkInfo.read(apk);
        write(output, List.of(apk), writer -> {
            if (format == ReportFormat.JSON) JSON.toJson(info, writer);
            else {
                writer.write("Package: " + info.packageName() + "\nVersion: " + info.versionName()
                        + " (" + info.versionCode() + ")\nDeclared min/target SDK: "
                        + info.minSdkVersion() + "/" + info.targetSdkVersion() + "\n");
                for (String permission : info.permissions()) writer.write("Permission: " + permission + "\n");
                for (ApkInfo.Component component : info.components()) {
                    writer.write(component.kind() + " " + component.name() + " declaredExported="
                            + component.declaredExported() + "\n");
                }
                writer.write("Values describe manifest declarations; null means absent or unresolved.\n");
            }
        });
        return 0;
    }
}
