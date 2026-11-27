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

// Portions derived from camunda-client-go, Copyright (c) 2019 Citilink LLC, MIT License
// (full notice in LICENSE-THIRD-PARTY and NOTICE).
// Ported from https://github.com/citilinkru/camunda-client-go (MIT): the fetch-and-lock polling loop, worker pool, panic recovery and failure reporting of processor/processor.go, reworked (context, slog, back-off with jitter, free-slot fetching)

package operaton

import (
	"context"
	"errors"
	"fmt"
	"math/rand/v2"
	"runtime/debug"
	"strings"
	"time"
)

// SubscribeOption customises a subscription.
type SubscribeOption func(*subscription)

type subscription struct {
	topic        string
	handler      Handler
	lockDuration time.Duration
	concurrency  int

	variables                []string
	localVariables           bool
	businessKey              string
	processDefinitionID      string
	processDefinitionIDs     []string
	processDefinitionKey     string
	processDefinitionKeys    []string
	processDefinitionVersion string
	tenantIDs                []string
	withoutTenantID          bool
	processVariables         map[string]any
	includeExtension         bool

	cancel context.CancelFunc
}

// WithLockDuration overrides Config.LockDuration for this topic.
func WithLockDuration(d time.Duration) SubscribeOption {
	return func(s *subscription) { s.lockDuration = d }
}

// WithVariables restricts the variables fetched with each task to these names. Without it all
// process variables are fetched.
func WithVariables(names ...string) SubscribeOption {
	return func(s *subscription) { s.variables = append(s.variables, names...) }
}

// WithLocalVariables makes the fetched variables local to the task's execution only.
func WithLocalVariables() SubscribeOption { return func(s *subscription) { s.localVariables = true } }

// WithBusinessKey only fetches tasks of process instances with this business key.
func WithBusinessKey(key string) SubscribeOption {
	return func(s *subscription) { s.businessKey = key }
}

// WithProcessDefinitionID only fetches tasks of this process definition id.
func WithProcessDefinitionID(id string) SubscribeOption {
	return func(s *subscription) { s.processDefinitionID = id }
}

// WithProcessDefinitionIDs only fetches tasks of these process definition ids.
func WithProcessDefinitionIDs(ids ...string) SubscribeOption {
	return func(s *subscription) { s.processDefinitionIDs = append(s.processDefinitionIDs, ids...) }
}

// WithProcessDefinitionKey only fetches tasks of this process definition key.
func WithProcessDefinitionKey(key string) SubscribeOption {
	return func(s *subscription) { s.processDefinitionKey = key }
}

// WithProcessDefinitionKeys only fetches tasks of these process definition keys.
func WithProcessDefinitionKeys(keys ...string) SubscribeOption {
	return func(s *subscription) { s.processDefinitionKeys = append(s.processDefinitionKeys, keys...) }
}

// WithProcessDefinitionVersionTag only fetches tasks of definitions with this version tag.
func WithProcessDefinitionVersionTag(tag string) SubscribeOption {
	return func(s *subscription) { s.processDefinitionVersion = tag }
}

// WithTenantIDs only fetches tasks of these tenants.
func WithTenantIDs(ids ...string) SubscribeOption {
	return func(s *subscription) { s.tenantIDs = append(s.tenantIDs, ids...) }
}

// WithTenantID only fetches tasks of this tenant.
func WithTenantID(id string) SubscribeOption { return WithTenantIDs(id) }

// WithoutTenantID only fetches tasks that belong to no tenant. Combined with WithTenantIDs the
// engine returns both.
func WithoutTenantID() SubscribeOption { return func(s *subscription) { s.withoutTenantID = true } }

// WithProcessVariablesFilter only fetches tasks of process instances whose variables have these
// values (equality filter; values are sent as plain JSON values).
func WithProcessVariablesFilter(filter map[string]any) SubscribeOption {
	return func(s *subscription) {
		if s.processVariables == nil {
			s.processVariables = map[string]any{}
		}
		for k, v := range filter {
			s.processVariables[k] = v
		}
	}
}

// WithExtensionProperties also fetches the BPMN extension properties of the task.
func WithExtensionProperties() SubscribeOption {
	return func(s *subscription) { s.includeExtension = true }
}

// WithConcurrency overrides Config.MaxConcurrency for this topic.
func WithConcurrency(n int) SubscribeOption { return func(s *subscription) { s.concurrency = n } }

func (s *subscription) validate() error {
	if s.lockDuration <= 0 {
		return fmt.Errorf("lock duration must be positive, got %s", s.lockDuration)
	}
	if s.concurrency <= 0 {
		return fmt.Errorf("concurrency must be positive, got %d", s.concurrency)
	}
	return nil
}

// topicRequest is the topic part of a fetch-and-lock request.
type topicRequest struct {
	TopicName                   string         `json:"topicName"`
	LockDuration                int64          `json:"lockDuration"`
	Variables                   []string       `json:"variables,omitempty"`
	LocalVariables              bool           `json:"localVariables,omitempty"`
	BusinessKey                 string         `json:"businessKey,omitempty"`
	ProcessDefinitionID         string         `json:"processDefinitionId,omitempty"`
	ProcessDefinitionIDIn       []string       `json:"processDefinitionIdIn,omitempty"`
	ProcessDefinitionKey        string         `json:"processDefinitionKey,omitempty"`
	ProcessDefinitionKeyIn      []string       `json:"processDefinitionKeyIn,omitempty"`
	ProcessDefinitionVersionTag string         `json:"processDefinitionVersionTag,omitempty"`
	WithoutTenantID             bool           `json:"withoutTenantId,omitempty"`
	TenantIDIn                  []string       `json:"tenantIdIn,omitempty"`
	ProcessVariables            map[string]any `json:"processVariables,omitempty"`
	DeserializeValues           bool           `json:"deserializeValues"`
	IncludeExtensionProperties  bool           `json:"includeExtensionProperties,omitempty"`
}

type fetchRequest struct {
	WorkerID             string         `json:"workerId"`
	MaxTasks             int            `json:"maxTasks"`
	UsePriority          bool           `json:"usePriority,omitempty"`
	AsyncResponseTimeout int64          `json:"asyncResponseTimeout,omitempty"`
	Topics               []topicRequest `json:"topics"`
}

func (s *subscription) request(workerID string, maxTasks int, usePriority bool, async time.Duration) fetchRequest {
	return fetchRequest{
		WorkerID:             workerID,
		MaxTasks:             maxTasks,
		UsePriority:          usePriority,
		AsyncResponseTimeout: async.Milliseconds(),
		Topics: []topicRequest{{
			TopicName:                   s.topic,
			LockDuration:                s.lockDuration.Milliseconds(),
			Variables:                   s.variables,
			LocalVariables:              s.localVariables,
			BusinessKey:                 s.businessKey,
			ProcessDefinitionID:         s.processDefinitionID,
			ProcessDefinitionIDIn:       s.processDefinitionIDs,
			ProcessDefinitionKey:        s.processDefinitionKey,
			ProcessDefinitionKeyIn:      s.processDefinitionKeys,
			ProcessDefinitionVersionTag: s.processDefinitionVersion,
			WithoutTenantID:             s.withoutTenantID,
			TenantIDIn:                  s.tenantIDs,
			ProcessVariables:            s.processVariables,
			// Keep serialized Object values as strings: no implicit conversion.
			DeserializeValues:          false,
			IncludeExtensionProperties: s.includeExtension,
		}},
	}
}

// poll is the fetch loop of one subscription. A free-slot semaphore bounds the running handlers;
// each fetch asks for no more tasks than there are free slots.
func (c *Client) poll(ctx context.Context, s *subscription) {
	log := c.log.With("topic", s.topic)
	sem := make(chan struct{}, s.concurrency)
	bo := newBackoff(c.cfg.Backoff)
	log.Info("polling", "lockDuration", s.lockDuration, "concurrency", s.concurrency, "maxTasks", c.cfg.MaxTasks)

	for {
		n, ok := acquireSlots(ctx, sem, c.cfg.MaxTasks)
		if !ok {
			return
		}
		var tasks []*Task
		fetchCtx, cancel := context.WithTimeout(ctx, c.cfg.AsyncResponseTimeout+c.cfg.RequestTimeout)
		err := c.api.do(fetchCtx, "POST", "/external-task/fetchAndLock",
			s.request(c.cfg.WorkerID, n, c.cfg.UsePriority, c.cfg.AsyncResponseTimeout), &tasks)
		cancel()
		if err != nil {
			releaseSlots(sem, n)
			if ctx.Err() != nil {
				return
			}
			d := bo.next()
			log.Error("fetch and lock failed, backing off", "error", err, "retryIn", d)
			if !sleep(ctx, d) {
				return
			}
			continue
		}
		bo.reset()
		if len(tasks) > n {
			// Never expected; the surplus tasks are locked, so they are processed anyway,
			// just without a concurrency slot.
			log.Warn("engine returned more tasks than requested", "requested", n, "returned", len(tasks))
		}
		if len(tasks) < n {
			releaseSlots(sem, n-len(tasks))
		}
		for i, t := range tasks {
			holdsSlot := i < n
			c.wg.Add(1)
			go func() {
				defer c.wg.Done()
				if holdsSlot {
					defer func() { <-sem }()
				}
				c.process(s, t)
			}()
		}
		if len(tasks) == 0 && c.cfg.AsyncResponseTimeout == 0 {
			if !sleep(ctx, c.cfg.PollInterval) {
				return
			}
		}
	}
}

// acquireSlots blocks for one free slot, then takes up to max-1 more without blocking.
func acquireSlots(ctx context.Context, sem chan struct{}, max int) (int, bool) {
	select {
	case sem <- struct{}{}:
	case <-ctx.Done():
		return 0, false
	}
	n := 1
	for n < max {
		select {
		case sem <- struct{}{}:
			n++
		default:
			return n, true
		}
	}
	return n, true
}

func releaseSlots(sem chan struct{}, n int) {
	for i := 0; i < n; i++ {
		<-sem
	}
}

func sleep(ctx context.Context, d time.Duration) bool {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-t.C:
		return true
	case <-ctx.Done():
		return false
	}
}

// backoff is an exponential back-off with +-20% jitter.
type backoff struct {
	cfg     Backoff
	current time.Duration
}

func newBackoff(cfg Backoff) *backoff { return &backoff{cfg: cfg} }

func (b *backoff) next() time.Duration {
	if b.current == 0 {
		b.current = b.cfg.Initial
	} else {
		b.current = time.Duration(float64(b.current) * b.cfg.Factor)
		if b.current > b.cfg.Max {
			b.current = b.cfg.Max
		}
	}
	jitter := 0.8 + rand.Float64()*0.4
	d := time.Duration(float64(b.current) * jitter)
	if d > b.cfg.Max {
		d = b.cfg.Max
	}
	return d
}

func (b *backoff) reset() { b.current = 0 }

// panicError wraps a recovered handler panic.
type panicError struct {
	value any
	stack string
}

func (p *panicError) Error() string { return fmt.Sprintf("handler panicked: %v", p.value) }

func callHandler(ctx context.Context, h Handler, t *Task, svc *TaskService) (err error) {
	defer func() {
		if r := recover(); r != nil {
			err = &panicError{value: r, stack: string(debug.Stack())}
		}
	}()
	return h(ctx, t, svc)
}

// process runs the handler for one task and reports its outcome to the engine.
func (c *Client) process(s *subscription, t *Task) {
	log := c.log.With("topic", s.topic, "taskId", t.ID, "processInstanceId", t.ProcessInstanceID)
	svc := newTaskService(c.api, c.cfg.WorkerID, t, c.cfg.RequestTimeout)
	log.Debug("handling task")
	handlerErr := callHandler(c.handlerCtx, s.handler, t, svc)

	// Reporting must survive a graceful stop that cancelled the handler context.
	ctx := context.WithoutCancel(c.handlerCtx)

	if handlerErr == nil {
		if svc.handled() {
			log.Debug("task handled", "state", stateNames[svc.state.Load()])
			return
		}
		if err := svc.Complete(ctx, nil); err != nil {
			log.Error("completing task after handler returned failed; the lock will expire and the task is fetched again", "error", err)
			return
		}
		log.Debug("task completed by client (handler returned nil without reporting)")
		return
	}

	if svc.handled() {
		log.Error("handler returned an error after the task was already handled", "state", stateNames[svc.state.Load()], "error", handlerErr)
		return
	}

	var bpmnErr *BPMNError
	if errors.As(handlerErr, &bpmnErr) {
		log.Info("handler reported BPMN error", "code", bpmnErr.Code, "message", bpmnErr.Message)
		if err := svc.HandleBPMNError(ctx, bpmnErr.Code, bpmnErr.Message, bpmnErr.Variables); err != nil {
			log.Error("reporting BPMN error failed; the lock will expire and the task is fetched again", "error", err)
		}
		return
	}

	f := c.failureFor(t, handlerErr)
	log.Error("handler failed", "error", handlerErr, "retries", f.Retries, "retryTimeout", f.RetryTimeout)
	if err := svc.HandleFailure(ctx, f); err != nil {
		log.Error("reporting failure failed; the lock will expire and the task is fetched again", "error", err)
	}
}

const maxMessageLen = 500

// failureFor derives the failure report: explicit FailureError values win, otherwise the engine's
// retry counter counts down starting at Config.Retries-1.
func (c *Client) failureFor(t *Task, err error) Failure {
	f := Failure{Message: truncate(err.Error(), maxMessageLen), Details: err.Error(), RetryTimeout: c.cfg.RetryTimeout}
	if t.Retries == nil {
		f.Retries = c.cfg.Retries - 1
	} else if *t.Retries > 0 {
		f.Retries = *t.Retries - 1
	}
	var pe *panicError
	if errors.As(err, &pe) {
		f.Details = pe.Error() + "\n" + pe.stack
	}
	var fe *FailureError
	if errors.As(err, &fe) {
		f.Retries, f.RetryTimeout = fe.Retries, fe.RetryTimeout
		if fe.Details != "" {
			f.Details = fe.Details
		}
	}
	return f
}

func truncate(s string, n int) string {
	r := []rune(s)
	if len(r) <= n {
		return s
	}
	return strings.TrimSpace(string(r[:n-1])) + "…"
}
