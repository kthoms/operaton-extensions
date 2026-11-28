/** A request as seen by interceptors, right before it is sent. */
export interface RequestConfig {
  url: string;
  method: string;
  headers: Record<string, string>;
  body?: string;
}

/** Changes a request before it is sent: add headers, rewrite the URL, ... May be async. */
export type RequestInterceptor = (request: RequestConfig) => RequestConfig | Promise<RequestConfig>;

export interface BasicAuth {
  type: "basic";
  username: string;
  password: string;
}

export interface BearerAuth {
  type: "bearer";
  /** The token, or a function returning the current token (called for every request). */
  token: string | (() => string | Promise<string>);
}

export type Auth = BasicAuth | BearerAuth;

/** Interceptor that sends HTTP basic credentials. */
export function basicAuth(username: string, password: string): RequestInterceptor {
  const value = `Basic ${Buffer.from(`${username}:${password}`, "utf-8").toString("base64")}`;
  return (request) => ({ ...request, headers: { ...request.headers, Authorization: value } });
}

/** Interceptor that sends a bearer token; a function is called for every request (token refresh). */
export function bearerAuth(token: BearerAuth["token"]): RequestInterceptor {
  return async (request) => {
    const value = typeof token === "function" ? await token() : token;
    if (!value) {
      throw new Error("bearer token is empty");
    }
    return { ...request, headers: { ...request.headers, Authorization: `Bearer ${value}` } };
  };
}

/** The interceptor for an `auth` option. */
export function authInterceptor(auth: Auth): RequestInterceptor {
  switch (auth.type) {
    case "basic":
      return basicAuth(auth.username, auth.password);
    case "bearer":
      return bearerAuth(auth.token);
  }
}
