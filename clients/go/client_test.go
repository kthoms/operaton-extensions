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
	"errors"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestFetchAndLockRequestAndComplete(t *testing.T) {
	f := newFakeEngine(t)
	f.queue("[" + taskJSON("1", "null", "") + "]")
	c := testClient(t, f, func(cfg *Config) {
		cfg.MaxTasks = 3
		cfg.UsePriority = true
		cfg.LockDuration = 45 * time.Second
	})
	got := make(chan *Task, 1)
	_, err := c.Subscribe("credit-check", func(ctx context.Context, task *Task, svc *TaskService) error {
		got <- task
		return svc.Complete(ctx, Variables{"approved": Boolean(true)})
	}, WithVariables("amount", "name"), WithBusinessKey("bk"), WithProcessDefinitionKeys("a", "b"),
		WithTenantIDs("t1"), WithLocalVariables(), WithLockDuration(10*time.Second))
	if err != nil {
		t.Fatal(err)
	}
	if err := c.Start(); err != nil {
		t.Fatal(err)
	}
	task := <-got
	amount, err := task.RequireVariable("amount")
	if err != nil {
		t.Fatal(err)
	}
	if n, _ := amount.AsInt64(); n != 42 {
		t.Fatalf("amount = %d", n)
	}
	complete := f.waitFor("/external-task/1/complete")
	if complete.Method != "POST" || complete.Body["workerId"] != "w1" {
		t.Fatalf("complete = %+v", complete)
	}
	vars := complete.Body["variables"].(map[string]any)["approved"].(map[string]any)
	if vars["type"] != "Boolean" || vars["value"] != true {
		t.Fatalf("variables = %+v", vars)
	}

	fetch := f.requests()[0]
	if fetch.Path != "/external-task/fetchAndLock" {
		t.Fatalf("first request = %+v", fetch)
	}
	if fetch.Body["workerId"] != "w1" || fetch.Body["maxTasks"] != float64(3) ||
		fetch.Body["usePriority"] != true || fetch.Body["asyncResponseTimeout"] != float64(5000) {
		t.Fatalf("fetch body = %+v", fetch.Body)
	}
	topic := fetch.Body["topics"].([]any)[0].(map[string]any)
	for k, want := range map[string]any{
		"topicName": "credit-check", "lockDuration": float64(10000), "businessKey": "bk",
		"localVariables": true, "deserializeValues": false,
	} {
		if topic[k] != want {
			t.Errorf("topic[%s] = %v, want %v", k, topic[k], want)
		}
	}
	if keys := topic["processDefinitionKeyIn"].([]any); len(keys) != 2 || keys[1] != "b" {
		t.Errorf("processDefinitionKeyIn = %v", keys)
	}
	if ids := topic["tenantIdIn"].([]any); len(ids) != 1 || ids[0] != "t1" {
		t.Errorf("tenantIdIn = %v", ids)
	}
	if vs := topic["variables"].([]any); len(vs) != 2 {
		t.Errorf("variables = %v", vs)
	}
}

func TestHandlerNilWithoutReportCompletesTask(t *testing.T) {
	f := newFakeEngine(t)
	f.queue("[" + taskJSON("1", "null", "") + "]")
	c := testClient(t, f, nil)
	mustSubscribe(t, c, "t", func(context.Context, *Task, *TaskService) error { return nil })
	mustStart(t, c)
	r := f.waitFor("/external-task/1/complete")
	if r.Body["workerId"] != "w1" {
		t.Fatalf("complete = %+v", r)
	}
}

func TestHandlerErrorReportsFailureWithRetryCountdown(t *testing.T) {
	cases := []struct {
		name        string
		retries     string
		wantRetries float64
	}{
		{"first failure leaves Config.Retries-1", "null", 2},
		{"counts down", "2", 1},
		{"stops at zero", "1", 0},
		{"zero stays zero", "0", 0},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			f := newFakeEngine(t)
			f.queue("[" + taskJSON("1", tc.retries, "") + "]")
			c := testClient(t, f, func(cfg *Config) { cfg.RetryTimeout = 2 * time.Second })
			mustSubscribe(t, c, "t", func(context.Context, *Task, *TaskService) error {
				return errors.New("backend down")
			})
			mustStart(t, c)
			r := f.waitFor("/external-task/1/failure")
			if r.Body["retries"] != tc.wantRetries || r.Body["retryTimeout"] != float64(2000) ||
				r.Body["errorMessage"] != "backend down" || r.Body["workerId"] != "w1" {
				t.Fatalf("failure = %+v", r.Body)
			}
		})
	}
}

func TestFailureErrorControlsRetries(t *testing.T) {
	f := newFakeEngine(t)
	f.queue("[" + taskJSON("1", "null", "") + "]")
	c := testClient(t, f, nil)
	mustSubscribe(t, c, "t", func(context.Context, *Task, *TaskService) error {
		return Fail(errors.New("rate limited"), 7, 90*time.Second)
	})
	mustStart(t, c)
	r := f.waitFor("/external-task/1/failure")
	if r.Body["retries"] != float64(7) || r.Body["retryTimeout"] != float64(90000) {
		t.Fatalf("failure = %+v", r.Body)
	}

	f2 := newFakeEngine(t)
	f2.queue("[" + taskJSON("2", "5", "") + "]")
	c2 := testClient(t, f2, nil)
	mustSubscribe(t, c2, "t", func(context.Context, *Task, *TaskService) error {
		return NoRetry(errors.New("fatal"))
	})
	mustStart(t, c2)
	if r := f2.waitFor("/external-task/2/failure"); r.Body["retries"] != float64(0) {
		t.Fatalf("NoRetry failure = %+v", r.Body)
	}
}

func TestBPMNErrorFromHandler(t *testing.T) {
	f := newFakeEngine(t)
	f.queue("[" + taskJSON("1", "null", "") + "]")
	c := testClient(t, f, nil)
	mustSubscribe(t, c, "t", func(context.Context, *Task, *TaskService) error {
		return &BPMNError{Code: "NO_CREDIT", Message: "limit exceeded", Variables: Variables{"why": String("limit")}}
	})
	mustStart(t, c)
	r := f.waitFor("/external-task/1/bpmnError")
	if r.Body["errorCode"] != "NO_CREDIT" || r.Body["errorMessage"] != "limit exceeded" || r.Body["workerId"] != "w1" {
		t.Fatalf("bpmnError = %+v", r.Body)
	}
	if r.Body["variables"].(map[string]any)["why"] == nil {
		t.Fatalf("variables missing: %+v", r.Body)
	}
	for _, q := range f.requests() {
		if strings.HasSuffix(q.Path, "/failure") {
			t.Fatal("a BPMN error must not also be reported as failure")
		}
	}
}

func TestWrappedBPMNErrorIsRecognised(t *testing.T) {
	f := newFakeEngine(t)
	f.queue("[" + taskJSON("1", "null", "") + "]")
	c := testClient(t, f, nil)
	mustSubscribe(t, c, "t", func(context.Context, *Task, *TaskService) error {
		return errors.Join(errors.New("context"), &BPMNError{Code: "X"})
	})
	mustStart(t, c)
	f.waitFor("/external-task/1/bpmnError")
}

func TestPanicBecomesFailureWithStack(t *testing.T) {
	f := newFakeEngine(t)
	f.queue("[" + taskJSON("1", "null", "") + "]")
	c := testClient(t, f, nil)
	mustSubscribe(t, c, "t", func(context.Context, *Task, *TaskService) error { panic("kaboom") })
	mustStart(t, c)
	r := f.waitFor("/external-task/1/failure")
	if !strings.Contains(r.Body["errorMessage"].(string), "kaboom") ||
		!strings.Contains(r.Body["errorDetails"].(string), "goroutine") {
		t.Fatalf("failure = %+v", r.Body)
	}
}

func TestErrorAfterTaskAlreadyHandledIsNotReportedTwice(t *testing.T) {
	f := newFakeEngine(t)
	f.queue("[" + taskJSON("1", "null", "") + "]")
	c := testClient(t, f, nil)
	done := make(chan struct{})
	mustSubscribe(t, c, "t", func(ctx context.Context, _ *Task, svc *TaskService) error {
		defer close(done)
		if err := svc.Complete(ctx, nil); err != nil {
			return err
		}
		return errors.New("late error")
	})
	mustStart(t, c)
	<-done
	f.waitFor("/external-task/1/complete")
	stopOK(t, c)
	for _, q := range f.requests() {
		if strings.HasSuffix(q.Path, "/failure") {
			t.Fatal("failure reported for an already completed task")
		}
	}
}

func TestTaskServiceOperations(t *testing.T) {
	f := newFakeEngine(t)
	f.queue("[" + taskJSON("1", "null", "") + "]")
	c := testClient(t, f, nil)
	errs := make(chan error, 1)
	mustSubscribe(t, c, "t", func(ctx context.Context, task *Task, svc *TaskService) error {
		if err := svc.ExtendLock(ctx, 90*time.Second); err != nil {
			return err
		}
		if err := svc.SetVariables(ctx, Variables{"a": String("b")}); err != nil {
			return err
		}
		if err := svc.SetLocalVariables(ctx, Variables{"l": Long(1)}); err != nil {
			return err
		}
		if err := svc.Unlock(ctx); err != nil {
			return err
		}
		// A second terminal call must fail locally.
		err := svc.Complete(ctx, nil)
		errs <- err
		return nil
	})
	mustStart(t, c)
	if err := <-errs; !errors.Is(err, ErrAlreadyHandled) {
		t.Fatalf("second terminal call: %v", err)
	}
	if r := f.waitFor("/external-task/1/extendLock"); r.Body["newDuration"] != float64(90000) || r.Body["workerId"] != "w1" {
		t.Fatalf("extendLock = %+v", r.Body)
	}
	if r := f.waitFor("/process-instance/pi-1/variables"); r.Body["modifications"].(map[string]any)["a"] == nil {
		t.Fatalf("setVariables = %+v", r.Body)
	}
	f.waitFor("/execution/ex-1/localVariables")
	f.waitFor("/external-task/1/unlock")
	stopOK(t, c)
	for _, q := range f.requests() {
		if strings.HasSuffix(q.Path, "/complete") {
			t.Fatal("complete must not be sent after unlock")
		}
	}
}

func TestEngineErrorOnReportKeepsTaskOpenForRetryReport(t *testing.T) {
	f := newFakeEngine(t)
	f.status["/complete"] = 400
	f.queue("[" + taskJSON("1", "null", "") + "]")
	c := testClient(t, f, nil)
	res := make(chan error, 1)
	mustSubscribe(t, c, "t", func(ctx context.Context, _ *Task, svc *TaskService) error {
		err := svc.Complete(ctx, nil)
		res <- err
		return err
	})
	mustStart(t, c)
	err := <-res
	var ae *APIError
	if !errors.As(err, &ae) || ae.StatusCode != 400 || ae.Type != "InvalidRequestException" {
		t.Fatalf("expected APIError 400, got %v", err)
	}
	// The failed complete released the claim, so the handler's error is reported as failure.
	f.waitFor("/external-task/1/failure")
}

func TestAuthentication(t *testing.T) {
	cases := []struct {
		name string
		mod  func(*Config)
		want string
	}{
		{"basic", func(c *Config) { c.Username, c.Password = "demo", "pw" }, "Basic ZGVtbzpwdw=="},
		{"bearer", func(c *Config) { c.BearerToken = "tok" }, "Bearer tok"},
		{"bearer func", func(c *Config) { c.BearerTokenFunc = func() (string, error) { return "dyn", nil } }, "Bearer dyn"},
		{"none", nil, ""},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			f := newFakeEngine(t)
			f.queue("[" + taskJSON("1", "null", "") + "]")
			c := testClient(t, f, tc.mod)
			mustSubscribe(t, c, "t", func(context.Context, *Task, *TaskService) error { return nil })
			mustStart(t, c)
			r := f.waitFor("/external-task/1/complete")
			if r.Auth != tc.want {
				t.Fatalf("complete Authorization = %q, want %q", r.Auth, tc.want)
			}
			if first := f.requests()[0]; first.Auth != tc.want {
				t.Fatalf("fetch Authorization = %q, want %q", first.Auth, tc.want)
			}
		})
	}
}

func TestCustomHTTPClientIsUsedAndNotModified(t *testing.T) {
	f := newFakeEngine(t)
	var used atomic.Int32
	hc := &testHTTPClient{used: &used}
	cl := hc.client()
	orig := cl.Transport
	c := testClient(t, f, func(cfg *Config) { cfg.HTTPClient = cl; cfg.BearerToken = "x" })
	f.queue("[" + taskJSON("1", "null", "") + "]")
	mustSubscribe(t, c, "t", func(context.Context, *Task, *TaskService) error { return nil })
	mustStart(t, c)
	f.waitFor("/external-task/1/complete")
	if used.Load() == 0 {
		t.Fatal("custom transport was not used")
	}
	if cl.Transport != orig {
		t.Fatal("the caller's http.Client was modified")
	}
}

func TestBackoffAfterFetchErrorsThenRecovers(t *testing.T) {
	f := newFakeEngine(t)
	f.failFetch = 3
	f.queue("[" + taskJSON("1", "null", "") + "]")
	c := testClient(t, f, nil)
	mustSubscribe(t, c, "t", func(context.Context, *Task, *TaskService) error { return nil })
	mustStart(t, c)
	f.waitFor("/external-task/1/complete")
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.fetchN < 4 {
		t.Fatalf("fetches = %d, want at least 4 (3 failed + 1 ok)", f.fetchN)
	}
}

func TestBackoffGrowsAndIsCapped(t *testing.T) {
	b := newBackoff(Backoff{Initial: 100 * time.Millisecond, Max: time.Second, Factor: 2})
	var last time.Duration
	for i := 0; i < 10; i++ {
		d := b.next()
		if d > time.Second {
			t.Fatalf("step %d: %s exceeds max", i, d)
		}
		last = d
	}
	if last < 800*time.Millisecond {
		t.Fatalf("after 10 failures the pause is %s, expected close to the cap", last)
	}
	b.reset()
	if d := b.next(); d > 130*time.Millisecond {
		t.Fatalf("after reset first pause is %s", d)
	}
}

func TestConcurrencyIsBoundedAndFetchAsksForFreeSlotsOnly(t *testing.T) {
	f := newFakeEngine(t)
	f.queue("[" + taskJSON("1", "null", "") + "," + taskJSON("2", "null", "") + "]")
	f.queue("[" + taskJSON("3", "null", "") + "]")
	c := testClient(t, f, func(cfg *Config) { cfg.MaxTasks = 5 })
	var running, peak atomic.Int32
	release := make(chan struct{})
	mustSubscribe(t, c, "t", func(ctx context.Context, _ *Task, svc *TaskService) error {
		n := running.Add(1)
		for {
			p := peak.Load()
			if n <= p || peak.CompareAndSwap(p, n) {
				break
			}
		}
		<-release
		running.Add(-1)
		return nil
	}, WithConcurrency(2))
	mustStart(t, c)
	waitUntil(t, "two handlers running", func() bool { return running.Load() == 2 })
	// With both slots busy no further fetch may be in flight: the first fetch asked for 2 (all
	// slots free would be 2 here because concurrency is 2, maxTasks 5).
	if first := f.requests()[0]; first.Body["maxTasks"] != float64(2) {
		t.Fatalf("maxTasks of first fetch = %v, want 2", first.Body["maxTasks"])
	}
	time.Sleep(100 * time.Millisecond)
	if n := running.Load(); n != 2 {
		t.Fatalf("running = %d while saturated", n)
	}
	close(release)
	f.waitFor("/external-task/3/complete")
	if peak.Load() > 2 {
		t.Fatalf("peak concurrency %d exceeds 2", peak.Load())
	}
}

func TestGracefulStopWaitsForRunningHandler(t *testing.T) {
	f := newFakeEngine(t)
	f.queue("[" + taskJSON("1", "null", "") + "]")
	c := testClient(t, f, nil)
	started := make(chan struct{})
	release := make(chan struct{})
	mustSubscribe(t, c, "t", func(ctx context.Context, _ *Task, svc *TaskService) error {
		close(started)
		<-release
		return svc.Complete(ctx, nil)
	})
	mustStart(t, c)
	<-started
	stopped := make(chan error, 1)
	go func() { stopped <- c.Stop(testCtx(t)) }()
	select {
	case err := <-stopped:
		t.Fatalf("Stop returned while a handler was running: %v", err)
	case <-time.After(150 * time.Millisecond):
	}
	close(release)
	if err := <-stopped; err != nil {
		t.Fatal(err)
	}
	f.waitFor("/external-task/1/complete")
}

func TestStopTimeoutCancelsHandlerContext(t *testing.T) {
	f := newFakeEngine(t)
	f.queue("[" + taskJSON("1", "null", "") + "]")
	c := testClient(t, f, nil)
	started := make(chan struct{})
	cancelled := make(chan struct{})
	mustSubscribe(t, c, "t", func(ctx context.Context, _ *Task, _ *TaskService) error {
		close(started)
		<-ctx.Done()
		close(cancelled)
		return ctx.Err()
	})
	mustStart(t, c)
	<-started
	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer cancel()
	err := c.Stop(ctx)
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("Stop = %v, want deadline exceeded", err)
	}
	select {
	case <-cancelled:
	case <-time.After(5 * time.Second):
		t.Fatal("handler context was not cancelled")
	}
	// The failure of the interrupted handler is still reported.
	f.waitFor("/external-task/1/failure")
}

func TestStopStopsFetching(t *testing.T) {
	f := newFakeEngine(t)
	c := testClient(t, f, nil)
	mustSubscribe(t, c, "t", func(context.Context, *Task, *TaskService) error { return nil })
	mustStart(t, c)
	waitUntil(t, "first fetch", func() bool { return len(f.requests()) > 0 })
	stopOK(t, c)
	n := len(f.requests())
	time.Sleep(100 * time.Millisecond)
	if len(f.requests()) != n {
		t.Fatal("requests after Stop")
	}
	if err := c.Stop(testCtx(t)); !errors.Is(err, ErrStopped) {
		t.Fatalf("second Stop = %v", err)
	}
	if _, err := c.Subscribe("x", func(context.Context, *Task, *TaskService) error { return nil }); !errors.Is(err, ErrStopped) {
		t.Fatalf("Subscribe after Stop = %v", err)
	}
	if err := c.Start(); !errors.Is(err, ErrStopped) {
		t.Fatalf("Start after Stop = %v", err)
	}
}

func TestSubscribeValidationAndUnsubscribe(t *testing.T) {
	f := newFakeEngine(t)
	c := testClient(t, f, nil)
	h := func(context.Context, *Task, *TaskService) error { return nil }
	if _, err := c.Subscribe("", h); err == nil {
		t.Error("empty topic accepted")
	}
	if _, err := c.Subscribe("t", nil); err == nil {
		t.Error("nil handler accepted")
	}
	if _, err := c.Subscribe("t", h, WithLockDuration(-1)); err == nil {
		t.Error("negative lock duration accepted")
	}
	if _, err := c.Subscribe("t", h, WithConcurrency(0)); err == nil {
		t.Error("zero concurrency accepted")
	}
	unsub, err := c.Subscribe("t", h)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := c.Subscribe("t", h); !errors.Is(err, ErrDuplicateTopic) {
		t.Fatalf("duplicate = %v", err)
	}
	mustStart(t, c)
	if err := c.Start(); !errors.Is(err, ErrAlreadyStarted) {
		t.Fatalf("second Start = %v", err)
	}
	waitUntil(t, "polling", func() bool { return len(f.requests()) > 0 })
	unsub()
	unsub() // idempotent
	time.Sleep(100 * time.Millisecond)
	n := len(f.requests())
	time.Sleep(150 * time.Millisecond)
	if len(f.requests()) != n {
		t.Fatal("still polling after unsubscribe")
	}
	// The topic is free again, and a subscription added after Start polls immediately.
	f.queue("[" + taskJSON("9", "null", "") + "]")
	if _, err := c.Subscribe("t", h); err != nil {
		t.Fatal(err)
	}
	f.waitFor("/external-task/9/complete")
}

func TestLongPollingDisabledUsesPollInterval(t *testing.T) {
	f := newFakeEngine(t)
	c := testClient(t, f, func(cfg *Config) {
		cfg.AsyncResponseTimeout = -1
		cfg.PollInterval = 50 * time.Millisecond
	})
	mustSubscribe(t, c, "t", func(context.Context, *Task, *TaskService) error { return nil })
	mustStart(t, c)
	time.Sleep(400 * time.Millisecond)
	stopOK(t, c)
	reqs := f.requests()
	if len(reqs) < 3 || len(reqs) > 12 {
		t.Fatalf("fetches in 400ms with a 50ms interval = %d", len(reqs))
	}
	if _, present := reqs[0].Body["asyncResponseTimeout"]; present {
		t.Fatalf("asyncResponseTimeout must be omitted, got %v", reqs[0].Body["asyncResponseTimeout"])
	}
}

func TestConfigValidation(t *testing.T) {
	bad := map[string]Config{
		"scheme":         {BaseURL: "ftp://x"},
		"no host":        {BaseURL: "http://"},
		"negative tasks": {MaxTasks: -1},
		"negative lock":  {LockDuration: -time.Second},
		"basic+bearer":   {Username: "a", Password: "b", BearerToken: "t"},
		"token+func":     {BearerToken: "t", BearerTokenFunc: func() (string, error) { return "", nil }},
		"password only":  {Password: "b"},
		"backoff order":  {Backoff: Backoff{Initial: time.Minute, Max: time.Second}},
		"backoff factor": {Backoff: Backoff{Factor: 0.5}},
	}
	for name, cfg := range bad {
		if _, err := New(cfg); err == nil {
			t.Errorf("%s: expected an error", name)
		}
	}
	c, err := New(Config{})
	if err != nil {
		t.Fatal(err)
	}
	if c.cfg.BaseURL != DefaultBaseURL || c.cfg.MaxTasks != 10 || c.cfg.LockDuration != 30*time.Second ||
		c.cfg.AsyncResponseTimeout != 30*time.Second || c.cfg.Retries != 3 || !strings.HasPrefix(c.WorkerID(), "operaton-go-") {
		t.Fatalf("defaults wrong: %+v", c.cfg)
	}
	if c2, _ := New(Config{BaseURL: "http://h/engine-rest/"}); c2.cfg.BaseURL != "http://h/engine-rest" {
		t.Fatalf("trailing slash kept: %s", c2.cfg.BaseURL)
	}
}

func TestRunStopsOnContextCancel(t *testing.T) {
	f := newFakeEngine(t)
	f.queue("[" + taskJSON("1", "null", "") + "]")
	c := testClient(t, f, nil)
	mustSubscribe(t, c, "t", func(context.Context, *Task, *TaskService) error { return nil })
	ctx, cancel := context.WithCancel(context.Background())
	var wg sync.WaitGroup
	var runErr error
	wg.Add(1)
	go func() { defer wg.Done(); runErr = c.Run(ctx) }()
	f.waitFor("/external-task/1/complete")
	cancel()
	wg.Wait()
	if runErr != nil {
		t.Fatal(runErr)
	}
}

func TestAPIErrorFormatting(t *testing.T) {
	e := &APIError{Method: "POST", URL: "http://x/y", StatusCode: 404, Type: "RestException", Message: "gone"}
	if got := e.Error(); !strings.Contains(got, "HTTP 404") || !strings.Contains(got, "RestException: gone") {
		t.Fatal(got)
	}
	if !IsNotFound(e) || IsNotFound(errors.New("x")) {
		t.Fatal("IsNotFound wrong")
	}
}

func mustSubscribe(t *testing.T, c *Client, topic string, h Handler, opts ...SubscribeOption) {
	t.Helper()
	if _, err := c.Subscribe(topic, h, opts...); err != nil {
		t.Fatal(err)
	}
}

func mustStart(t *testing.T, c *Client) {
	t.Helper()
	if err := c.Start(); err != nil {
		t.Fatal(err)
	}
}

func stopOK(t *testing.T, c *Client) {
	t.Helper()
	if err := c.Stop(testCtx(t)); err != nil {
		t.Fatal(err)
	}
}
