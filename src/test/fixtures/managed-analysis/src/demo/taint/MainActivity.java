package demo.taint;

import android.app.Activity;
import android.os.Bundle;

/** Minimal positive fixture: onCreate passes a configured source return to a configured sink. */
public final class MainActivity extends Activity {
    private static volatile String lastSinkValue;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String sensitiveValue = source();
        sink(sensitiveValue);
    }

    /**
     * Returns the value treated as sensitive by the fixture analysis.
     * @return a constant fixture value
     */
    public static String source() {
        return "androspecter-fixture-secret";
    }

    /**
     * Stores the value passed to the fixture sink.
     * @param value value observed by the analysis
     */
    public static void sink(String value) {
        lastSinkValue = value;
    }
}
