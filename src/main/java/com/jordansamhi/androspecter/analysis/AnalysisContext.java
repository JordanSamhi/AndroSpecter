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

    public static AnalysisContext capture() {
        return capture(CancellationToken.none());
    }

    public static AnalysisContext capture(CancellationToken cancellation) {
        return capture(UUID.randomUUID(), cancellation);
    }

    static AnalysisContext capture(UUID id, CancellationToken cancellation) {
        return new AnalysisContext(id, cancellation);
    }

    /** Stable identity to associate detached snapshots with this analysis. */
    public UUID id() {
        return id;
    }

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

    /** Lifecycle validity on the calling thread; cancellation is checked by checkValid(). */
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

    @Override
    public void close() {
        checkOwner();
        closed = true;
    }
}
