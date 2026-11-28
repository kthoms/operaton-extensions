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
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// recorded is one request received by the fake engine.
type recorded struct {
	Method, Path, Auth string
	Body               map[string]any
}

// fakeEngine is a minimal REST stand-in: fetchAndLock answers from a queue (empty list otherwise,
// after a short pause to emulate long polling), every other call is recorded and answered 204.
type fakeEngine struct {
	t      *testing.T
	srv    *httptest.Server
	mu     sync.Mutex
	reqs   []recorded
	tasks  []string // JSON task objects served by the next fetches, one batch per element
	fetchN int
	// failFetch makes the first N fetches answer HTTP 500.
	failFetch int
	// status overrides the answer for a path suffix.
	status map[string]int
}

func newFakeEngine(t *testing.T) *fakeEngine {
	f := &fakeEngine{t: t, status: map[string]int{}}
	f.srv = httptest.NewServer(http.HandlerFunc(f.handle))
	t.Cleanup(f.srv.Close)
	return f
}

func (f *fakeEngine) url() string { return f.srv.URL + "/engine-rest" }

func (f *fakeEngine) handle(w http.ResponseWriter, r *http.Request) {
	raw, _ := io.ReadAll(r.Body)
	var body map[string]any
	if len(raw) > 0 {
		if err := json.Unmarshal(raw, &body); err != nil {
			f.t.Errorf("request body is not JSON: %v: %s", err, raw)
		}
	}
	f.mu.Lock()
	f.reqs = append(f.reqs, recorded{r.Method, strings.TrimPrefix(r.URL.Path, "/engine-rest"), r.Header.Get("Authorization"), body})
	isFetch := strings.HasSuffix(r.URL.Path, "/fetchAndLock")
	var answer string
	failing := false
	if isFetch {
		f.fetchN++
		if f.failFetch > 0 {
			f.failFetch--
			failing = true
		} else if len(f.tasks) > 0 {
			answer = f.tasks[0]
			f.tasks = f.tasks[1:]
		}
	}
	override := 0
	for suffix, code := range f.status {
		if strings.HasSuffix(r.URL.Path, suffix) {
			override = code
		}
	}
	f.mu.Unlock()

	if failing {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(500)
		_, _ = w.Write([]byte(`{"type":"RuntimeException","message":"boom"}`))
		return
	}
	if override != 0 {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(override)
		_, _ = w.Write([]byte(`{"type":"InvalidRequestException","message":"nope"}`))
		return
	}
	if isFetch {
		if answer == "" {
			select { // emulate a (short) long poll
			case <-time.After(20 * time.Millisecond):
			case <-r.Context().Done():
			}
			answer = "[]"
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(answer))
		return
	}
	w.WriteHeader(204)
}

func (f *fakeEngine) queue(batch string) {
	f.mu.Lock()
	f.tasks = append(f.tasks, batch)
	f.mu.Unlock()
}

func (f *fakeEngine) requests() []recorded {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]recorded(nil), f.reqs...)
}

// waitFor returns the first non-fetch request whose path ends with suffix.
func (f *fakeEngine) waitFor(suffix string) recorded {
	f.t.Helper()
	var found recorded
	waitUntil(f.t, "request "+suffix, func() bool {
		for _, r := range f.requests() {
			if strings.HasSuffix(r.Path, suffix) {
				found = r
				return true
			}
		}
		return false
	})
	return found
}

func waitUntil(t *testing.T, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %s", what)
}

func taskJSON(id string, retries string, extra string) string {
	return `{"id":"` + id + `","topicName":"t","processInstanceId":"pi-` + id + `","executionId":"ex-` + id + `","retries":` + retries + `,"variables":{"amount":{"type":"Long","value":42,"valueInfo":{}}}` + extra + `}`
}

func testClient(t *testing.T, f *fakeEngine, mod func(*Config)) *Client {
	t.Helper()
	cfg := Config{
		BaseURL:              f.url(),
		WorkerID:             "w1",
		AsyncResponseTimeout: 5 * time.Second,
		Backoff:              Backoff{Initial: 10 * time.Millisecond, Max: 40 * time.Millisecond},
		RequestTimeout:       5 * time.Second,
		Logger:               slog.New(slog.NewTextHandler(io.Discard, nil)),
	}
	if mod != nil {
		mod(&cfg)
	}
	c, err := New(cfg)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = c.Stop(testCtx(t)) })
	return c
}

func testCtx(t *testing.T) context.Context {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	t.Cleanup(cancel)
	return ctx
}

// testHTTPClient counts the requests that pass through its transport.
type testHTTPClient struct{ used *atomic.Int32 }

func (h *testHTTPClient) client() *http.Client {
	return &http.Client{Transport: countingTransport{h.used}}
}

type countingTransport struct{ n *atomic.Int32 }

func (c countingTransport) RoundTrip(r *http.Request) (*http.Response, error) {
	c.n.Add(1)
	return http.DefaultTransport.RoundTrip(r)
}
