// This app is a thin client over the API Gateway with the JWT kept in
// localStorage -- there is nothing meaningful to render on the server, and
// SSR would need a different auth-storage strategy entirely. Disabling it
// keeps the app a plain client-rendered SPA, matching the "avoid advanced
// SSR" scope for this frontend.
export const ssr = false;
