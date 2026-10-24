package com.jordansamhi.androspecter;

import com.jordansamhi.androspecter.analysis.AnalysisContext;
import com.jordansamhi.androspecter.inspection.Inspection;
import soot.*;

/**
 * Legacy visitor adapter. Subclass it using the default no-argument constructor and override only the
 * callbacks needed. All three examination levels are initially enabled; disable unused levels to avoid
 * unnecessary body loading. Prefer {@link Inspection} for explicit callback registration and filtering.
 * By default every loaded scene class is visited, including framework, library, and dummy-main classes.
 * Traversal does not discover classes missing from the scene or restrict itself to application classes.
 * <p>Callbacks receive borrowed, mutable Soot objects from the current scene. Use those objects only on
 * the analysis's owning thread while its context remains valid. Callback edits affect the live scene;
 * collection snapshots tolerate edits but do not guarantee visits to newly added objects. Callbacks must
 * preserve valid bodies and must not reset Soot. This adapter does not invalidate cached analysis graphs.
 * Instances retain mutable examination settings and are not designed for concurrent runs or configuration.
 *
 * @author <a href="https://jordansamhi.com">Jordan Samhi</a>
 * @deprecated use {@link Inspection} for composable callbacks and predicates
 */
@Deprecated
public abstract class AndroidAppInspector {

    private boolean examineClasses = true;
    private boolean examineMethods = true;
    private boolean examineStatements = true;

    /**
     * Receives a class before its method and statement callbacks. The default implementation does nothing.
     *
     * @param sc borrowed class from the inspected scene
     */
    public void examineClass(SootClass sc) {}

    /**
     * Receives a method, including abstract and native methods, before its statement callbacks.
     * The default implementation does nothing and does not load a body.
     *
     * @param sc borrowed class declaring {@code sm}
     * @param sm borrowed method being examined
     */
    public void examineMethod(SootClass sc, SootMethod sm) {}

    /**
     * Receives each unit in a concrete method, including identity and return statements.
     * The method's body is loaded before visiting its units. The default implementation does nothing.
     *
     * @param sc borrowed class declaring {@code sm}
     * @param sm borrowed concrete method containing {@code u}
     * @param u borrowed unit from the method's active body
     */
    public void examineStatement(SootClass sc, SootMethod sm, Unit u) {}

    /**
     * Replaces the enabled callback levels for subsequent runs. Levels are independent: disabling class
     * callbacks does not disable method or statement traversal. Disabling statement callbacks prevents
     * this adapter from loading bodies; disabling all levels makes a run only validate its context.
     * Callback code can still load bodies itself. A run captures these switches when constructing its
     * inspection plan, so changing them from a callback affects a later run, not the active traversal.
     *
     * @param examineClasses whether to invoke {@link #examineClass(SootClass)}
     * @param examineMethods whether to invoke {@link #examineMethod(SootClass, SootMethod)}
     * @param examineStatements whether to invoke {@link #examineStatement(SootClass, SootMethod, Unit)}
     */
    public void setExaminationCriteria(boolean examineClasses, boolean examineMethods, boolean examineStatements) {
        this.examineClasses = examineClasses;
        this.examineMethods = examineMethods;
        this.examineStatements = examineStatements;
    }

    /**
     * Inspects the current scene using a temporary context captured on the calling thread.
     * The temporary context is closed afterward without resetting the scene. This does not initialize
     * Soot or load an application; use {@link #runIn(AnalysisContext)} to retain an existing context's
     * cancellation policy. Callback and body-loading failures propagate without undoing callback edits.
     * Capturing the temporary context does not acquire an exclusive analysis lease; the caller must already
     * control Soot. Closing it does not close or invalidate an existing managed analysis context.
     *
     * @throws IllegalStateException if a callback invalidates the context by resetting Soot
     * @throws java.util.concurrent.CancellationException if thread interruption is observed
     * @throws RuntimeException if a callback or Soot body-loading operation fails
     */
    public void run() {
        try (AnalysisContext context = AnalysisContext.capture()) {
            runIn(context);
        }
    }

    /**
     * Inspects all current scene classes using the enabled callback levels and an existing context.
     * The context is checked around callbacks and remains owned by the caller; this method does not close it.
     * Class callbacks precede method callbacks, which precede the method's statement callbacks.
     * Each class's methods and each concrete method's units are visited before moving to the next class;
     * this is not a global classes-first/methods-second pass. Removed objects in traversal snapshots may
     * still be visited, and newly added objects are not guaranteed a visit. Bodies are not validated afterward.
     *
     * @param context valid context for the current Soot scene
     * @throws NullPointerException if {@code context} is null
     * @throws IllegalStateException if the context is closed, belongs to another thread, or Soot was reset
     * @throws java.util.concurrent.CancellationException if cancellation or interruption is observed
     * @throws RuntimeException if a callback or Soot body-loading operation fails
     */
    public void runIn(AnalysisContext context) {
        Inspection.Builder inspection = Inspection.builder();
        if (examineClasses) inspection.onClass(this::examineClass);
        if (examineMethods) inspection.onMethod(method -> examineMethod(method.getDeclaringClass(), method));
        if (examineStatements) inspection.onStatement((method, unit) ->
                examineStatement(method.getDeclaringClass(), method, unit));
        inspection.build().run(context);
    }
}
