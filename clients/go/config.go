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
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"
)

// DefaultBaseURL is the REST API base URL used when Config.BaseURL is empty.
const DefaultBaseURL = "http://localhost:8080/engine-rest"

// Config configures a Client. The zero value is usable: every field has a default.
type Config struct {
	// BaseURL is the REST API root of the engine, including the /engine-rest path
	// (default DefaultBaseURL).
	BaseURL string

	// WorkerID identifies this worker when it locks tasks (default "operaton-go-<host>-<random>").
	WorkerID string

	// MaxTasks is the maximum number of tasks requested per fetch-and-lock call (default 10).
	MaxTasks int

	// MaxConcurrency bounds the handlers running at the same time per subscription
	// (default MaxTasks). A subscription never fetches more tasks than it has free slots.
	MaxConcurrency int

	// AsyncResponseTimeout is the long polling timeout: how long the engine holds a fetch-and-lock
	// request open when no task is available (default 30s). A negative value disables long
	// polling; the client then waits PollInterval after an empty answer.
	AsyncResponseTimeout time.Duration

	// PollInterval is the pause after an empty fetch when long polling is disabled (default 1s).
	PollInterval time.Duration

	// LockDuration is how long fetched tasks stay locked for this worker (default 30s). A
	// subscription can override it.
	LockDuration time.Duration

	// UsePriority makes the engine return tasks in priority order.
	UsePriority bool

	// Retries is the total number of attempts for a task, the first one included (default 3). The
	// engine task starts without a retry counter, so the first failure reports Retries-1 remaining
	// retries; later failures count down from the value the engine reports. At zero the engine
	// raises an incident.
	Retries int

	// RetryTimeout is the delay before a failed task is available again (default 0).
	RetryTimeout time.Duration

	// Backoff configures the pause after failed fetch-and-lock calls.
	Backoff Backoff

	// RequestTimeout bounds every REST call except the long-polling fetch, which gets
	// AsyncResponseTimeout plus RequestTimeout (default 30s).
	RequestTimeout time.Duration

	// ShutdownTimeout is how long Run waits for running handlers after its context ends
	// (default 30s).
	ShutdownTimeout time.Duration

	// Username and Password enable HTTP basic authentication.
	Username string
	Password string

	// BearerToken enables bearer authentication with a static token. BearerTokenFunc is called
	// for every request instead, for tokens that rotate. Basic and bearer settings are mutually
	// exclusive.
	BearerToken     string
	BearerTokenFunc func() (string, error)

	// HTTPClient is used for all requests (default: a client without overall timeout, since the
	// client applies per-request deadlines). Its transport is wrapped for authentication; the
	// HTTPClient itself is not modified.
	HTTPClient *http.Client

	// Logger receives structured log records (default slog.Default()).
	Logger *slog.Logger
}

// Backoff is an exponential back-off with jitter.
type Backoff struct {
	// Initial is the first pause (default 500ms).
	Initial time.Duration
	// Max caps the pause (default 60s).
	Max time.Duration
	// Factor multiplies the pause after every consecutive failure (default 2).
	Factor float64
}

func (c Config) withDefaults() (Config, error) {
	if c.BaseURL == "" {
		c.BaseURL = DefaultBaseURL
	}
	u, err := url.Parse(c.BaseURL)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
		return c, fmt.Errorf("operaton: invalid BaseURL %q: want an http(s) URL such as %s", c.BaseURL, DefaultBaseURL)
	}
	c.BaseURL = strings.TrimRight(c.BaseURL, "/")

	if c.WorkerID == "" {
		c.WorkerID = generateWorkerID()
	}
	for _, f := range []struct {
		name string
		v    int
	}{{"MaxTasks", c.MaxTasks}, {"MaxConcurrency", c.MaxConcurrency}, {"Retries", c.Retries}} {
		if f.v < 0 {
			return c, fmt.Errorf("operaton: %s must not be negative, got %d", f.name, f.v)
		}
	}
	for _, f := range []struct {
		name string
		v    time.Duration
	}{{"PollInterval", c.PollInterval},
		{"LockDuration", c.LockDuration}, {"RetryTimeout", c.RetryTimeout},
		{"RequestTimeout", c.RequestTimeout}, {"ShutdownTimeout", c.ShutdownTimeout},
		{"Backoff.Initial", c.Backoff.Initial}, {"Backoff.Max", c.Backoff.Max}} {
		if f.v < 0 {
			return c, fmt.Errorf("operaton: %s must not be negative, got %s", f.name, f.v)
		}
	}
	if c.Backoff.Factor < 0 {
		return c, fmt.Errorf("operaton: Backoff.Factor must not be negative, got %v", c.Backoff.Factor)
	}
	basic := c.Username != "" || c.Password != ""
	bearer := c.BearerToken != "" || c.BearerTokenFunc != nil
	if basic && bearer {
		return c, errors.New("operaton: basic authentication (Username/Password) and bearer authentication (BearerToken/BearerTokenFunc) are mutually exclusive")
	}
	if c.BearerToken != "" && c.BearerTokenFunc != nil {
		return c, errors.New("operaton: set either BearerToken or BearerTokenFunc, not both")
	}
	if basic && c.Username == "" {
		return c, errors.New("operaton: Password given without Username")
	}

	if c.MaxTasks == 0 {
		c.MaxTasks = 10
	}
	if c.MaxConcurrency == 0 {
		c.MaxConcurrency = c.MaxTasks
	}
	switch {
	case c.AsyncResponseTimeout == 0:
		c.AsyncResponseTimeout = 30 * time.Second
	case c.AsyncResponseTimeout < 0:
		c.AsyncResponseTimeout = 0
	}
	if c.PollInterval == 0 {
		c.PollInterval = time.Second
	}
	if c.LockDuration == 0 {
		c.LockDuration = 30 * time.Second
	}
	if c.Retries == 0 {
		c.Retries = 3
	}
	if c.RequestTimeout == 0 {
		c.RequestTimeout = 30 * time.Second
	}
	if c.ShutdownTimeout == 0 {
		c.ShutdownTimeout = 30 * time.Second
	}
	if c.Backoff.Initial == 0 {
		c.Backoff.Initial = 500 * time.Millisecond
	}
	if c.Backoff.Max == 0 {
		c.Backoff.Max = 60 * time.Second
	}
	if c.Backoff.Factor == 0 {
		c.Backoff.Factor = 2
	}
	if c.Backoff.Factor < 1 {
		return c, fmt.Errorf("operaton: Backoff.Factor must be at least 1, got %v", c.Backoff.Factor)
	}
	if c.Backoff.Max < c.Backoff.Initial {
		return c, fmt.Errorf("operaton: Backoff.Max (%s) is below Backoff.Initial (%s)", c.Backoff.Max, c.Backoff.Initial)
	}
	if c.Logger == nil {
		c.Logger = slog.Default()
	}
	return c, nil
}

func generateWorkerID() string {
	host, err := os.Hostname()
	if err != nil || host == "" {
		host = "unknown"
	}
	b := make([]byte, 4)
	if _, err := rand.Read(b); err != nil {
		return fmt.Sprintf("operaton-go-%s-%d", host, time.Now().UnixNano())
	}
	return "operaton-go-" + host + "-" + hex.EncodeToString(b)
}
