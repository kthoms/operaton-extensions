// Copyright 2026 the Operaton Extensions contributors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package operaton

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
)

// APIError is an error answer of the engine's REST API.
type APIError struct {
	Method     string
	URL        string
	StatusCode int
	// Type and Message are the engine's exception type and message, when the body had them.
	Type    string
	Message string
	// Body is the raw response body when it was not a JSON error.
	Body string
}

func (e *APIError) Error() string {
	detail := e.Message
	if detail == "" {
		detail = strings.TrimSpace(e.Body)
	}
	if e.Type != "" {
		detail = e.Type + ": " + detail
	}
	return fmt.Sprintf("operaton: %s %s: HTTP %d: %s", e.Method, e.URL, e.StatusCode, detail)
}

// IsNotFound reports whether err is an API error with HTTP status 404 (for example a task that no
// longer exists or whose lock was lost).
func IsNotFound(err error) bool {
	var ae *APIError
	return errors.As(err, &ae) && ae.StatusCode == http.StatusNotFound
}

type api struct {
	baseURL string
	http    *http.Client
}

func newAPI(cfg Config) *api {
	base := cfg.HTTPClient
	if base == nil {
		base = &http.Client{}
	}
	hc := *base // copy: the caller's client stays untouched
	rt := hc.Transport
	if rt == nil {
		rt = http.DefaultTransport
	}
	switch {
	case cfg.Username != "":
		hc.Transport = &authTransport{base: rt, header: func() (string, error) {
			req, _ := http.NewRequest(http.MethodGet, "http://x", nil)
			req.SetBasicAuth(cfg.Username, cfg.Password)
			return req.Header.Get("Authorization"), nil
		}}
	case cfg.BearerToken != "":
		token := cfg.BearerToken
		hc.Transport = &authTransport{base: rt, header: func() (string, error) { return "Bearer " + token, nil }}
	case cfg.BearerTokenFunc != nil:
		fn := cfg.BearerTokenFunc
		hc.Transport = &authTransport{base: rt, header: func() (string, error) {
			t, err := fn()
			if err != nil {
				return "", fmt.Errorf("operaton: obtain bearer token: %w", err)
			}
			if t == "" {
				return "", errors.New("operaton: bearer token function returned an empty token")
			}
			return "Bearer " + t, nil
		}}
	default:
		hc.Transport = rt
	}
	return &api{baseURL: cfg.BaseURL, http: &hc}
}

// authTransport adds the Authorization header to every request without mutating the original.
type authTransport struct {
	base   http.RoundTripper
	header func() (string, error)
}

func (t *authTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	h, err := t.header()
	if err != nil {
		return nil, err
	}
	clone := req.Clone(req.Context())
	clone.Header.Set("Authorization", h)
	return t.base.RoundTrip(clone)
}

// do sends a JSON request. body and out may be nil. A 2xx answer without body leaves out alone.
func (a *api) do(ctx context.Context, method, path string, body, out any) error {
	var rd io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return fmt.Errorf("operaton: encode request for %s %s: %w", method, path, err)
		}
		rd = bytes.NewReader(b)
	}
	u := a.baseURL + path
	req, err := http.NewRequestWithContext(ctx, method, u, rd)
	if err != nil {
		return fmt.Errorf("operaton: build request %s %s: %w", method, u, err)
	}
	req.Header.Set("Accept", "application/json")
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	resp, err := a.http.Do(req)
	if err != nil {
		return fmt.Errorf("operaton: %s %s: %w", method, u, err)
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(io.LimitReader(resp.Body, 64<<20))
	if err != nil {
		return fmt.Errorf("operaton: read response of %s %s: %w", method, u, err)
	}
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		ae := &APIError{Method: method, URL: u, StatusCode: resp.StatusCode}
		var eb struct {
			Type    string `json:"type"`
			Message string `json:"message"`
		}
		if json.Unmarshal(data, &eb) == nil && (eb.Type != "" || eb.Message != "") {
			ae.Type, ae.Message = eb.Type, eb.Message
		} else {
			ae.Body = string(data)
		}
		return ae
	}
	if out != nil && len(bytes.TrimSpace(data)) > 0 {
		if err := json.Unmarshal(data, out); err != nil {
			return fmt.Errorf("operaton: decode response of %s %s: %w", method, u, err)
		}
	}
	return nil
}

func pathEscape(s string) string { return url.PathEscape(s) }

func taskPath(id, action string) string {
	return "/external-task/" + url.PathEscape(id) + "/" + action
}
