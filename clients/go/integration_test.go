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

//go:build integration

package operaton_test

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"mime/multipart"
	"net/http"
	"net/url"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	operaton "github.com/kthoms/operaton-extensions/clients/go"
)

// Integration tests run against a real engine: OPERATON_URL is the REST base URL
// (default http://localhost:8080/engine-rest). Each test deploys its own process with a unique
// key and topic, so tests are independent and can run against a shared engine.

const bpmnTemplate = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  xmlns:operaton="http://operaton.org/schema/1.0/bpmn"
                  targetNamespace="http://operaton.org/test">
  <bpmn:process id="%[1]s" isExecutable="true" operaton:historyTimeToLive="1">
    <bpmn:startEvent id="Start"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>
    <bpmn:sequenceFlow id="f1" sourceRef="Start" targetRef="Work"/>
    <bpmn:serviceTask id="Work" name="Do work" operaton:type="external" operaton:topic="%[2]s">
      <bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing>
    </bpmn:serviceTask>
    <bpmn:sequenceFlow id="f2" sourceRef="Work" targetRef="EndDone"/>
    <bpmn:endEvent id="EndDone"><bpmn:incoming>f2</bpmn:incoming></bpmn:endEvent>
    <bpmn:boundaryEvent id="OnError" attachedToRef="Work">
      <bpmn:outgoing>f3</bpmn:outgoing>
      <bpmn:errorEventDefinition errorRef="Err"/>
    </bpmn:boundaryEvent>
    <bpmn:sequenceFlow id="f3" sourceRef="OnError" targetRef="EndHandled"/>
    <bpmn:endEvent id="EndHandled"><bpmn:incoming>f3</bpmn:incoming></bpmn:endEvent>
  </bpmn:process>
  <bpmn:error id="Err" errorCode="BUSINESS_ERROR"/>
</bpmn:definitions>`

type engine struct {
	t    *testing.T
	base string
	hc   *http.Client
}

func newEngine(t *testing.T) *engine {
	base := os.Getenv("OPERATON_URL")
	if base == "" {
		base = "http://localhost:8080/engine-rest"
	}
	return &engine{t: t, base: strings.TrimRight(base, "/"), hc: &http.Client{Timeout: 30 * time.Second}}
}

func (e *engine) do(method, path string, body, out any) {
	e.t.Helper()
	var rd io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			e.t.Fatal(err)
		}
		rd = bytes.NewReader(b)
	}
	req, err := http.NewRequest(method, e.base+path, rd)
	if err != nil {
		e.t.Fatal(err)
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	resp, err := e.hc.Do(req)
	if err != nil {
		e.t.Fatalf("%s %s: %v", method, path, err)
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(resp.Body)
	if resp.StatusCode/100 != 2 {
		e.t.Fatalf("%s %s: HTTP %d: %s", method, path, resp.StatusCode, data)
	}
	if out != nil {
		if err := json.Unmarshal(data, out); err != nil {
			e.t.Fatalf("%s %s: decode %s: %v", method, path, data, err)
		}
	}
}

// scenario is one deployed process with a unique key and topic.
type scenario struct {
	e     *engine
	key   string
	topic string
}

func deploy(t *testing.T) (*scenario, *engine) {
	t.Helper()
	e := newEngine(t)
	id := fmt.Sprintf("%s-%d", strings.ToLower(strings.NewReplacer("/", "-", "_", "-").Replace(t.Name())), time.Now().UnixNano()%1_000_000)
	if len(id) > 60 {
		id = id[len(id)-60:]
	}
	s := &scenario{e: e, key: "p-" + id, topic: "t-" + id}

	var buf bytes.Buffer
	mw := multipart.NewWriter(&buf)
	_ = mw.WriteField("deployment-name", s.key)
	fw, _ := mw.CreateFormFile("data", s.key+".bpmn")
	_, _ = fw.Write([]byte(fmt.Sprintf(bpmnTemplate, s.key, s.topic)))
	_ = mw.Close()
	req, _ := http.NewRequest("POST", e.base+"/deployment/create", &buf)
	req.Header.Set("Content-Type", mw.FormDataContentType())
	resp, err := e.hc.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(resp.Body)
	if resp.StatusCode/100 != 2 {
		t.Fatalf("deploy: HTTP %d: %s", resp.StatusCode, data)
	}
	return s, e
}

func (s *scenario) start(vars operaton.Variables, businessKey string) string {
	s.e.t.Helper()
	var out struct{ ID string }
	body := map[string]any{"variables": vars}
	if businessKey != "" {
		body["businessKey"] = businessKey
	}
	s.e.do("POST", "/process-definition/key/"+s.key+"/start", body, &out)
	if out.ID == "" {
		s.e.t.Fatal("no process instance id")
	}
	return out.ID
}

func (e *engine) await(what string, cond func() bool) {
	e.t.Helper()
	deadline := time.Now().Add(60 * time.Second)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(100 * time.Millisecond)
	}
	e.t.Fatalf("timed out waiting for %s", what)
}

func (e *engine) state(pi string) string {
	var out struct{ State string }
	e.do("GET", "/history/process-instance/"+pi, nil, &out)
	return out.State
}

func (e *engine) awaitState(pi, want string) {
	e.t.Helper()
	e.await("process instance "+pi+" to be "+want, func() bool { return e.state(pi) == want })
}

func (e *engine) variables(pi string) map[string]operaton.Variable {
	e.t.Helper()
	var list []struct {
		Name      string
		Type      string
		Value     json.RawMessage
		ValueInfo map[string]any
	}
	e.do("GET", "/history/variable-instance?deserializeValues=false&processInstanceId="+url.QueryEscape(pi), nil, &list)
	out := map[string]operaton.Variable{}
	for _, v := range list {
		out[v.Name] = operaton.Variable{Type: v.Type, Value: v.Value, ValueInfo: v.ValueInfo}
	}
	return out
}

func (e *engine) activityIDs(pi string) []string {
	e.t.Helper()
	var list []struct{ ActivityId string }
	e.do("GET", "/history/activity-instance?processInstanceId="+url.QueryEscape(pi), nil, &list)
	ids := make([]string, 0, len(list))
	for _, a := range list {
		ids = append(ids, a.ActivityId)
	}
	return ids
}

func newClient(t *testing.T, mod func(*operaton.Config)) *operaton.Client {
	t.Helper()
	cfg := operaton.Config{
		BaseURL:              newEngine(t).base,
		AsyncResponseTimeout: 5 * time.Second,
		LockDuration:         30 * time.Second,
		Logger:               slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelWarn})),
	}
	if mod != nil {
		mod(&cfg)
	}
	c, err := operaton.New(cfg)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		if err := c.Stop(ctx); err != nil && !errors.Is(err, operaton.ErrStopped) {
			t.Errorf("stop: %v", err)
		}
	})
	return c
}

func TestCompleteWithTypedVariables(t *testing.T) {
	s, e := deploy(t)
	c := newClient(t, nil)
	date := time.Date(2026, 5, 6, 7, 8, 9, 0, time.UTC)

	var gotDate time.Time
	var gotAmount, gotScore int64
	var gotRatio float64
	var gotFlag bool
	var gotCustomer struct {
		Name string `json:"name"`
		Tier int    `json:"tier"`
	}
	var handlerErr atomic.Value
	if _, err := c.Subscribe(s.topic, func(ctx context.Context, task *operaton.Task, svc *operaton.TaskService) error {
		read := func(name string) operaton.Variable {
			v, err := task.RequireVariable(name)
			if err != nil {
				handlerErr.Store(err)
			}
			return v
		}
		var err error
		gotAmount, err = read("amount").AsInt64()
		check(&handlerErr, err)
		gotScore, err = read("score").AsInt64()
		check(&handlerErr, err)
		gotRatio, err = read("ratio").AsFloat64()
		check(&handlerErr, err)
		gotFlag, err = read("flag").AsBool()
		check(&handlerErr, err)
		gotDate, err = read("due").AsTime()
		check(&handlerErr, err)
		check(&handlerErr, read("customer").AsJSON(&gotCustomer))
		if v, _ := task.Variable("nothing"); !v.IsNull() {
			handlerErr.Store(errors.New("nothing should be null"))
		}
		if task.ProcessDefinitionKey != s.key || task.TopicName != s.topic || task.ActivityID != "Work" {
			handlerErr.Store(fmt.Errorf("task metadata wrong: %+v", task))
		}
		return svc.Complete(ctx, operaton.Variables{
			"approved": operaton.Boolean(true),
			"limit":    operaton.Long(9007199254740993),
			"report":   operaton.JSON(map[string]any{"ok": true}),
			"file":     operaton.File("r.txt", "text/plain", []byte("hello")),
			"raw":      operaton.Bytes([]byte{1, 2, 3}),
			"when":     operaton.Date(date),
			"nothing":  operaton.Null(),
		})
	}); err != nil {
		t.Fatal(err)
	}
	if err := c.Start(); err != nil {
		t.Fatal(err)
	}

	pi := s.start(operaton.Variables{
		"amount":   operaton.Long(1500),
		"score":    operaton.Integer(712),
		"ratio":    operaton.Double(0.25),
		"flag":     operaton.Boolean(true),
		"due":      operaton.Date(date),
		"customer": operaton.JSON(map[string]any{"name": "Ada", "tier": 2}),
		"nothing":  operaton.Null(),
	}, "")
	e.awaitState(pi, "COMPLETED")

	if v := handlerErr.Load(); v != nil {
		t.Fatal(v)
	}
	if gotAmount != 1500 || gotScore != 712 || gotRatio != 0.25 || !gotFlag || !gotDate.Equal(date) ||
		gotCustomer.Name != "Ada" || gotCustomer.Tier != 2 {
		t.Fatalf("read values: %d %d %v %v %v %+v", gotAmount, gotScore, gotRatio, gotFlag, gotDate, gotCustomer)
	}

	vars := e.variables(pi)
	if b, err := vars["approved"].AsBool(); err != nil || !b {
		t.Errorf("approved = %v %v (%+v)", b, err, vars["approved"])
	}
	if l, err := vars["limit"].AsInt64(); err != nil || l != 9007199254740993 {
		t.Errorf("limit = %v %v (%+v)", l, err, vars["limit"])
	}
	var report struct{ OK bool }
	if err := vars["report"].AsJSON(&report); err != nil || !report.OK {
		t.Errorf("report = %+v %v (%+v)", report, err, vars["report"])
	}
	if f := vars["file"]; f.Type != "File" || f.FileName() != "r.txt" {
		t.Errorf("file = %+v", f)
	}
	if w, err := vars["when"].AsTime(); err != nil || !w.Equal(date) {
		t.Errorf("when = %v %v", w, err)
	}
	if got := e.activityIDs(pi); !contains(got, "EndDone") {
		t.Errorf("activities = %v, want EndDone", got)
	}
}

func check(slot *atomic.Value, err error) {
	if err != nil {
		slot.Store(err)
	}
}

func contains(list []string, s string) bool {
	for _, x := range list {
		if x == s {
			return true
		}
	}
	return false
}

func TestFailureIsRetriedThenRaisesIncident(t *testing.T) {
	s, e := deploy(t)
	c := newClient(t, func(cfg *operaton.Config) { cfg.Retries = 3 })
	var calls atomic.Int32
	var retriesSeen sync.Map
	if _, err := c.Subscribe(s.topic, func(ctx context.Context, task *operaton.Task, svc *operaton.TaskService) error {
		n := calls.Add(1)
		if task.Retries != nil {
			retriesSeen.Store(n, *task.Retries)
		}
		return errors.New("credit bureau unavailable")
	}); err != nil {
		t.Fatal(err)
	}
	if err := c.Start(); err != nil {
		t.Fatal(err)
	}
	pi := s.start(nil, "")

	var incidents []struct{ IncidentType, IncidentMessage string }
	e.await("an incident after the retries are used up", func() bool {
		e.do("GET", "/incident?processInstanceId="+pi, nil, &incidents)
		return len(incidents) > 0
	})
	if incidents[0].IncidentType != "failedExternalTask" || incidents[0].IncidentMessage != "credit bureau unavailable" {
		t.Fatalf("incident = %+v", incidents[0])
	}
	if n := calls.Load(); n != 3 {
		t.Fatalf("handler ran %d times, want 3 (Config.Retries)", n)
	}
	if r, _ := retriesSeen.Load(int32(2)); r != 2 {
		t.Errorf("retries seen on 2nd attempt = %v, want 2", r)
	}
	if r, _ := retriesSeen.Load(int32(3)); r != 1 {
		t.Errorf("retries seen on 3rd attempt = %v, want 1", r)
	}
	if st := e.state(pi); st != "ACTIVE" {
		t.Errorf("process state = %s, want ACTIVE (incident)", st)
	}
	var tasks []struct {
		Retries      int
		ErrorMessage string
	}
	e.do("GET", "/external-task?processInstanceId="+pi, nil, &tasks)
	if len(tasks) != 1 || tasks[0].Retries != 0 || tasks[0].ErrorMessage != "credit bureau unavailable" {
		t.Errorf("external task = %+v", tasks)
	}
}

func TestBPMNErrorIsCaughtByBoundaryEvent(t *testing.T) {
	s, e := deploy(t)
	c := newClient(t, nil)
	if _, err := c.Subscribe(s.topic, func(ctx context.Context, task *operaton.Task, svc *operaton.TaskService) error {
		return &operaton.BPMNError{Code: "BUSINESS_ERROR", Message: "no credit", Variables: operaton.Variables{"reason": operaton.String("limit exceeded")}}
	}); err != nil {
		t.Fatal(err)
	}
	if err := c.Start(); err != nil {
		t.Fatal(err)
	}
	pi := s.start(nil, "")
	e.awaitState(pi, "COMPLETED")
	if got := e.activityIDs(pi); !contains(got, "EndHandled") || contains(got, "EndDone") {
		t.Fatalf("activities = %v, want EndHandled and not EndDone", got)
	}
	if r, err := e.variables(pi)["reason"].AsString(); err != nil || r != "limit exceeded" {
		t.Fatalf("reason = %q %v", r, err)
	}
}

func TestExtendLockAndSetVariablesBeforeComplete(t *testing.T) {
	s, e := deploy(t)
	c := newClient(t, nil)
	var seenDuringWork atomic.Value
	if _, err := c.Subscribe(s.topic, func(ctx context.Context, task *operaton.Task, svc *operaton.TaskService) error {
		if err := svc.ExtendLock(ctx, 5*time.Minute); err != nil {
			return err
		}
		if err := svc.SetVariables(ctx, operaton.Variables{"progress": operaton.Integer(50)}); err != nil {
			return err
		}
		var tasks []struct{ LockExpirationTime string }
		req, _ := http.NewRequestWithContext(ctx, "GET", e.base+"/external-task?externalTaskId="+task.ID, nil)
		resp, err := e.hc.Do(req)
		if err != nil {
			return err
		}
		defer resp.Body.Close()
		if err := json.NewDecoder(resp.Body).Decode(&tasks); err != nil || len(tasks) != 1 {
			return fmt.Errorf("query task: %v %v", tasks, err)
		}
		seenDuringWork.Store(tasks[0].LockExpirationTime)
		return svc.Complete(ctx, operaton.Variables{"progress": operaton.Integer(100)})
	}); err != nil {
		t.Fatal(err)
	}
	if err := c.Start(); err != nil {
		t.Fatal(err)
	}
	pi := s.start(nil, "")
	e.awaitState(pi, "COMPLETED")
	exp, _ := seenDuringWork.Load().(string)
	ts := operaton.Timestamp{}
	if err := json.Unmarshal([]byte(`"`+exp+`"`), &ts); err != nil || time.Until(ts.Time) < 2*time.Minute {
		t.Fatalf("lock expiration after ExtendLock(5m) = %q (%v)", exp, err)
	}
	if p, err := e.variables(pi)["progress"].AsInt64(); err != nil || p != 100 {
		t.Fatalf("progress = %v %v", p, err)
	}
}

func TestUnlockMakesTaskAvailableAgain(t *testing.T) {
	s, e := deploy(t)
	c := newClient(t, nil)
	var calls atomic.Int32
	if _, err := c.Subscribe(s.topic, func(ctx context.Context, task *operaton.Task, svc *operaton.TaskService) error {
		if calls.Add(1) == 1 {
			return svc.Unlock(ctx)
		}
		return svc.Complete(ctx, nil)
	}); err != nil {
		t.Fatal(err)
	}
	if err := c.Start(); err != nil {
		t.Fatal(err)
	}
	pi := s.start(nil, "")
	e.awaitState(pi, "COMPLETED")
	if n := calls.Load(); n != 2 {
		t.Fatalf("handler ran %d times, want 2", n)
	}
}

func TestBusinessKeyFilterOnlyFetchesMatchingInstances(t *testing.T) {
	s, e := deploy(t)
	c := newClient(t, nil)
	var processed sync.Map
	if _, err := c.Subscribe(s.topic, func(ctx context.Context, task *operaton.Task, svc *operaton.TaskService) error {
		processed.Store(task.BusinessKey, true)
		return svc.Complete(ctx, nil)
	}, operaton.WithBusinessKey("order-1"), operaton.WithVariables("none")); err != nil {
		t.Fatal(err)
	}
	other := s.start(nil, "order-2")
	match := s.start(nil, "order-1")
	if err := c.Start(); err != nil {
		t.Fatal(err)
	}
	e.awaitState(match, "COMPLETED")
	time.Sleep(2 * time.Second) // give a wrongly matching fetch time to take the other task
	if st := e.state(other); st != "ACTIVE" {
		t.Fatalf("instance with another business key is %s, want ACTIVE", st)
	}
	if _, ok := processed.Load("order-2"); ok {
		t.Fatal("task of another business key was processed")
	}
}

func TestMultipleTasksAreProcessedConcurrently(t *testing.T) {
	s, e := deploy(t)
	c := newClient(t, func(cfg *operaton.Config) { cfg.MaxTasks = 4 })
	var running, peak atomic.Int32
	if _, err := c.Subscribe(s.topic, func(ctx context.Context, task *operaton.Task, svc *operaton.TaskService) error {
		n := running.Add(1)
		for {
			p := peak.Load()
			if n <= p || peak.CompareAndSwap(p, n) {
				break
			}
		}
		time.Sleep(500 * time.Millisecond)
		running.Add(-1)
		return svc.Complete(ctx, nil)
	}, operaton.WithConcurrency(4)); err != nil {
		t.Fatal(err)
	}
	var pis []string
	for i := 0; i < 8; i++ {
		pis = append(pis, s.start(nil, ""))
	}
	if err := c.Start(); err != nil {
		t.Fatal(err)
	}
	for _, pi := range pis {
		e.awaitState(pi, "COMPLETED")
	}
	if p := peak.Load(); p < 2 || p > 4 {
		t.Fatalf("peak concurrency = %d, want between 2 and 4", p)
	}
}

func TestGracefulStopFinishesRunningTask(t *testing.T) {
	s, e := deploy(t)
	c := newClient(t, nil)
	started := make(chan struct{})
	if _, err := c.Subscribe(s.topic, func(ctx context.Context, task *operaton.Task, svc *operaton.TaskService) error {
		close(started)
		time.Sleep(time.Second)
		return svc.Complete(ctx, nil)
	}); err != nil {
		t.Fatal(err)
	}
	if err := c.Start(); err != nil {
		t.Fatal(err)
	}
	pi := s.start(nil, "")
	<-started
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if err := c.Stop(ctx); err != nil {
		t.Fatal(err)
	}
	if st := e.state(pi); st != "COMPLETED" {
		t.Fatalf("after Stop the process is %s, want COMPLETED", st)
	}
}

func TestEngineErrorsSurfaceAsAPIError(t *testing.T) {
	s2, e := deploy(t)
	c := newClient(t, nil)
	var got atomic.Value
	// Completing a task the engine no longer knows yields an APIError.
	if _, err := c.Subscribe(s2.topic, func(ctx context.Context, task *operaton.Task, svc *operaton.TaskService) error {
		// Delete the process instance under the task, then try to complete it.
		req, _ := http.NewRequestWithContext(ctx, "DELETE", e.base+"/process-instance/"+task.ProcessInstanceID, nil)
		resp, err := e.hc.Do(req)
		if err != nil {
			return err
		}
		resp.Body.Close()
		err = svc.Complete(ctx, nil)
		got.Store(err)
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	if err := c.Start(); err != nil {
		t.Fatal(err)
	}
	s2.start(nil, "")
	e.await("complete error", func() bool { return got.Load() != nil })
	err, _ := got.Load().(error)
	var ae *operaton.APIError
	if !errors.As(err, &ae) || (ae.StatusCode != 404 && ae.StatusCode != 400 && ae.StatusCode != 500) {
		t.Fatalf("error = %v, want an APIError", err)
	}
}
