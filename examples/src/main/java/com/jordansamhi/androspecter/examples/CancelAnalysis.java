package com.jordansamhi.androspecter.examples;

import com.jordansamhi.androspecter.ResultsAccumulator;
import com.jordansamhi.androspecter.analysis.AndroidAnalysis;
import com.jordansamhi.androspecter.execution.CancellationSource;
import com.jordansamhi.androspecter.execution.Deadline;
import com.jordansamhi.androspecter.inspection.Inspection;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CancellationException;

/** Uses a daemon deadline to request cancellation while the owning thread runs an analysis. */
public final class CancelAnalysis {
    private CancelAnalysis() {}

    /**
     * Starts a deadline before opening the APK and inspects only the selected application
     * package. The same duration also bounds FlowDroid's callback-analysis phase. A request
     * may be observed later than the deadline; the owner closes the session only after its
     * running operation returns or throws. A quick operation may finish without cancellation.
     *
     * @param args APK path, SDK platforms directory, package prefix, and positive whole seconds
     * @throws IllegalArgumentException if arguments are invalid or seconds exceed Integer.MAX_VALUE
     * @throws RuntimeException if initialization or inspection fails for a non-cancellation reason
     */
    public static void main(String[] args) {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                    "Usage: CancelAnalysis <apk> <platforms> <package-prefix> <seconds>");
        }
        String prefix = args[2].strip();
        if (prefix.isEmpty() || prefix.equals(".")) {
            throw new IllegalArgumentException("Provide a nonempty application package prefix");
        }
        String classPrefix = prefix.endsWith(".") ? prefix : prefix + ".";
        int seconds = Integer.parseInt(args[3]);
        if (seconds <= 0) {
            throw new IllegalArgumentException("The deadline must be a positive number of seconds");
        }
        Duration duration = Duration.ofSeconds(seconds);

        try (CancellationSource cancellation = new CancellationSource();
             Deadline deadline = Deadline.after(duration, cancellation::cancel)) {
            try (AndroidAnalysis analysis = AndroidAnalysis.builder(Path.of(args[0]), Path.of(args[1]))
                    .cancellationToken(cancellation.token())
                    .callbackTimeout(duration)
                    .open()) {
                ResultsAccumulator counters = analysis.metrics();
                analysis.inspect(Inspection.builder()
                        .classes(clazz -> clazz.isApplicationClass()
                                && clazz.getName().startsWith(classPrefix))
                        .onInvoke((caller, statement, invocation) -> counters.incrementMetric("invocations"))
                        .build());
                System.out.println("Completed inspection; static invocation sites: "
                        + counters.getMetric("invocations"));
            } catch (CancellationException cancelled) {
                System.out.println("Cancellation observed; analysis cleanup completed.");
            }
            System.out.println("Deadline expired: " + deadline.isExpired());
        }
    }
}
