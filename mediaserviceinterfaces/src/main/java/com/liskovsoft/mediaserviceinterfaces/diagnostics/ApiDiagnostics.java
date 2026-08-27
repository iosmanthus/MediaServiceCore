package com.liskovsoft.mediaserviceinterfaces.diagnostics;

/**
 * A one-way channel out of the api layer, for whoever is collecting diagnostics.
 *
 * The api layer cannot depend on the app that embeds it, and the app cannot
 * reach into the api layer's internals, so neither can see the other's
 * reporting code. This interface sits in the module they both already depend
 * on and carries events across without either side knowing anything else about
 * the other.
 *
 * Nothing is attached by default, so the api layer reports into nowhere unless
 * an embedder opts in. Calls must never block or throw: the sink is invoked
 * from request paths, and diagnostics that can break playback are worse than no
 * diagnostics at all.
 */
public final class ApiDiagnostics {
    /** Receives events as a name plus alternating key/value pairs. */
    public interface Sink {
        void onApiEvent(String event, Object... keyValues);
    }

    private static volatile Sink sSink;

    private ApiDiagnostics() {
    }

    public static void setSink(Sink sink) {
        sSink = sink;
    }

    /** True when someone is listening, so callers can skip building an event. */
    public static boolean isEnabled() {
        return sSink != null;
    }

    public static void report(String event, Object... keyValues) {
        Sink sink = sSink;
        if (sink == null) {
            return;
        }
        try {
            sink.onApiEvent(event, keyValues);
        } catch (Throwable e) {
            // Whatever went wrong on the collecting side is not this side's
            // problem, and certainly not worth failing a request over.
            sSink = null;
        }
    }
}
