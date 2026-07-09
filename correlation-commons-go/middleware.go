package correlation

import "net/http"

// Middleware is the birthplace of a business operation's CorrelationId for
// this service's HTTP surface: reused from the X-Correlation-Id request
// header if the client already sent one, generated otherwise. RequestId is
// always freshly generated, once per request. Both are put in the request
// context for the lifetime of the request and echoed back as response
// headers.
func Middleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		incoming := r.Header.Get(CorrelationIDHeader)
		correlationID := incoming
		if correlationID == "" {
			correlationID = NewID()
		}
		requestID := NewID()

		w.Header().Set(CorrelationIDHeader, correlationID)
		w.Header().Set(RequestIDHeader, requestID)

		ctx := WithCorrelationID(r.Context(), correlationID)
		ctx = WithRequestID(ctx, requestID)

		next.ServeHTTP(w, r.WithContext(ctx))
	})
}
