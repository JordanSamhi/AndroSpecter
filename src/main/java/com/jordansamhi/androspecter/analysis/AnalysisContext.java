package com.jordansamhi.androspecter.analysis;

import com.jordansamhi.androspecter.execution.CancellationToken;
import soot.G;

import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.UUID;

/**
 * A thread-confined view of one Soot global-state generation. This detects a
 * subsequent {@link G#reset()}; it cannot make arbitrary external Soot use safe.
 * Closing a captured context invalidates it without resetting the caller's scene.
 */
public final class AnalysisContext implements AutoCloseable {
    private final UUID id;
    // A borrowed context must not keep an entire old Soot scene alive after reset.
    private final WeakReference<G> generation;
    private final Thread owner;
    private final CancellationToken cancellation;
    private volatile boolean closed;

    private AnalysisContext(UUID id, CancellationToken cancellation) {
        this.id = Objects.requireNonNull(id, "id");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        this.generation = new WeakReference<>(G.v());
        this.owner = Thread.currentThread();
    }

    /**
     * Borrows the current Soot generation on the calling thread without taking ownership
     * of it. The caller must initialize and coordinate Soot separately. Closing this
     * context does not reset Soot. Thread interruption is still checked at checkpoints.
     *
     * @return a new context with an independent identity and no explicit cancellation source
     */
    public static AnalysisContext capture() {
        return capture(CancellationToken.none());
    }

    /**
     * Borrows the current Soot generation with caller-supplied cancellation. Capture does
     * not check cancellation or acquire the managed-session lease used by AndroidAnalysis.
     * Use this for advanced operations on a scene whose lifecycle the caller owns.
     *
     * @param cancellation token checked by {@link #checkValid()}
     * @return a new context confined to the calling thread
     * @throws NullPointerException if {@code cancellation} is null
     */
    public static AnalysisContext capture(CancellationToken cancellation) {
        return capture(UUID.randomUUID(), cancellation);
    }

    static AnalysisContext capture(UUID id, CancellationToken cancellation) {
        return new AnalysisContext(id, cancellation);
    }

    /**
     * Returns the stable identity used to associate detached snapshots with this analysis.
     * This identity remains available after cancellation, closure, or a Soot reset.
     *
     * @return this context's immutable analysis identifier
     */
    public UUID id() {
        return id;
    }

    /**
     * Checks thread ownership, closure, Soot generation, and cancellation, in that order.
     * Call this before using borrowed Soot objects. A successful check does not protect
     * against uncoordinated external code resetting or modifying Soot afterward.
     *
     * @throws IllegalStateException if called on another thread, after closure, or after Soot reset
     * @throws java.util.concurrent.CancellationException if cancellation is requested or the
     *         calling thread is interrupted; the interrupt flag is preserved
     */
    public void checkValid() {
        checkOwner();
        if (closed) {
            throw new IllegalStateException("Analysis context is closed: " + id);
        }
        if (!ownsCurrentGeneration()) {
            throw new IllegalStateException("Soot was reset after analysis " + id + " was created");
        }
        cancellation.throwIfCancellationRequested();
    }

    /**
     * Tests lifecycle validity on the calling thread without checking cancellation.
     * Use {@link #checkValid()} when a cancellation checkpoint is also required.
     *
     * @return true only on the owning thread while this context is open and Soot has not reset
     */
    public boolean isValid() {
        return !closed && owner == Thread.currentThread() && ownsCurrentGeneration();
    }

    boolean ownsCurrentGeneration() {
        return generation.get() == G.v();
    }

    void checkOwner() {
        if (owner != Thread.currentThread()) {
            throw new IllegalStateException("Analysis " + id + " must be used on its creating thread");
        }
    }

    /**
     * Invalidates this view without resetting Soot or releasing an AndroidAnalysis lease.
     * Repeated calls on the owning thread are harmless. For a context obtained from
     * {@link AndroidAnalysis#context()}, close the AndroidAnalysis itself to release its
     * resources, even if this view has already been closed.
     *
     * @throws IllegalStateException if called from a thread other than the capturing thread
     */
    @Override
    public void close() {
        checkOwner();
        closed = true;
    }
}
