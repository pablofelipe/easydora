package correlation

import (
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// The envelope is an Outbox-internal implementation detail only: it lets
// the outbox publisher promote correlationId/messageId to native AMQP
// properties at actual publish time, without a new database column and
// without changing the wire shape of the event body itself once
// published.
func TestOutboxEnvelope_RoundTripsAJsonObjectBody(t *testing.T) {
	stored := WrapOutboxPayload("corr-1", "msg-1", `{"orderId":"abc","success":true}`)

	correlationID, messageID, body, err := UnwrapOutboxPayload(stored)

	require.NoError(t, err)
	assert.Equal(t, "corr-1", correlationID)
	assert.Equal(t, "msg-1", messageID)
	assert.Equal(t, `{"orderId":"abc","success":true}`, body)
}
