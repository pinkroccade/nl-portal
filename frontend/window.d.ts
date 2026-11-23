declare global {
  interface Window {
    AUTO_IDLE_SESSION_LOGOUT?: string;
    IDLE_TIMEOUT_MINUTES?: string;
    GRAPHQL_URI: string;
    REST_URI: string;
  }
}

export {};
