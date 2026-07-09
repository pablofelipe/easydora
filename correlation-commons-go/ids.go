package correlation

import (
	"crypto/rand"
	"fmt"
)

// NewID generates a random v4 UUID string. Implemented directly on top of
// crypto/rand instead of pulling in a UUID library -- this is the only
// place in the service that needs one, so a ~10-line generator avoids an
// external dependency for a single call site.
func NewID() string {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		panic(fmt.Sprintf("correlation: failed to read random bytes: %v", err))
	}

	b[6] = (b[6] & 0x0f) | 0x40 // version 4
	b[8] = (b[8] & 0x3f) | 0x80 // variant 10

	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}
