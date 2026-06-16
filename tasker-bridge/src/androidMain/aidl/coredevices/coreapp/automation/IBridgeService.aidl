// Bridge command/query/recovery surface (HLDD-002 §3). All payloads are JSON strings so the app
// and an independently-updated client can evolve without Parcelable breakage. Every call is
// caller-verified in code (no custom permissions — ADR-003).
package coredevices.coreapp.automation;

import coredevices.coreapp.automation.IBridgeEventListener;

interface IBridgeService {
    // Client identifies; bridge verifies the caller and returns BridgeHello JSON, or an error
    // envelope ({ok:false,error:{code:...}}) e.g. CONSENT_PENDING / NOT_AUTHORIZED / CERT_MISMATCH.
    String handshake(in String helloJson);

    // Snapshot query (e.g. connected watches). Returns StateResult JSON or an error envelope.
    String getState(in String queryJson);

    // Recovery: events with seq > fromSeq for the given bootId. Returns EventBatch JSON.
    String getEventsSince(long fromSeq, in String bootId);

    // Live push registration. The bridge replays events since fromSeq then streams new ones.
    void registerEventListener(in String clientToken, IBridgeEventListener cb, long fromSeq);
    void unregisterEventListener(in String clientToken);

    // Tasker -> app/watch command. clientToken is from handshake; commandJson is a CommandEnvelope.
    // Returns a ResultEnvelope JSON ({ok:true,data:{...}} on success, or an error envelope e.g.
    // NOT_AUTHORIZED / UNSUPPORTED_COMMAND / RATE_LIMITED / INVALID_ARGS / INTERNAL). Caller-verified
    // (same ceremony as registerEventListener), command-tier-gated, allowlisted and rate-limited.
    // MUST remain the LAST method so transaction codes 1-5 stay stable for older paired clients.
    String execute(in String clientToken, in String commandJson);
}
