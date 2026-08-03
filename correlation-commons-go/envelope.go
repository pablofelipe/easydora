package correlation

import "encoding/json"

// outboxEnvelope is the Outbox-internal carrier for correlationId/
// messageId/traceparent across the write-now-publish-later gap. Never
// seen by anything outside the Outbox mechanism itself: the outbox
// publisher unwraps it and publishes only Body, promoting
// CorrelationID/MessageID to native AMQP properties and TraceParent to a
// producer span parent -- the wire shape of the actual event is
// unchanged. Mirrors auth-service's OutboxEnvelope (Java).
//
// TraceParent may be empty: an outbox row written outside any traced
// request/message is a legitimate, unremarkable state, not an error --
// see docs/adr/0024's 2026-08-03 Update.
type outboxEnvelope struct {
	CorrelationID string `json:"correlationId"`
	MessageID     string `json:"messageId"`
	TraceParent   string `json:"traceparent"`
	Body          string `json:"body"`
}

// WrapOutboxPayload builds the stored representation of an outbox row's
// payload column. body is carried as an opaque string regardless of
// whether it is itself JSON-object-shaped or something else.
func WrapOutboxPayload(correlationID, messageID, traceParent, body string) string {
	envelope := outboxEnvelope{CorrelationID: correlationID, MessageID: messageID, TraceParent: traceParent, Body: body}
	encoded, err := json.Marshal(envelope)
	if err != nil {
		// Only unencodable inputs (e.g. invalid UTF-8) reach here; correlationID/
		// messageID are always our own generated strings and body is always
		// a string, so this path is not expected to be exercised in practice.
		panic(err)
	}
	return string(encoded)
}

// UnwrapOutboxPayload decodes a payload previously produced by
// WrapOutboxPayload.
func UnwrapOutboxPayload(stored string) (correlationID, messageID, traceParent, body string, err error) {
	var envelope outboxEnvelope
	if err := json.Unmarshal([]byte(stored), &envelope); err != nil {
		return "", "", "", "", err
	}
	return envelope.CorrelationID, envelope.MessageID, envelope.TraceParent, envelope.Body, nil
}
