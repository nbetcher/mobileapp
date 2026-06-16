// Client-side callback the bridge pushes events to over the verified binding (HLDD-002 §3).
package coredevices.coreapp.automation;

interface IBridgeEventListener {
    // A batch of EventEnvelope JSON (wrapped as EventBatch).
    void onEvents(in String eventBatchJson);
    // Graceful shutdown / revocation notice.
    void onBridgeGoodbye(in String reasonJson);
}
