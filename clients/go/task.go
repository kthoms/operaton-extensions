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
// Ported from https://github.com/citilinkru/camunda-client-go (MIT): the task, complete, failure, BPMN error and extend-lock request shapes of external-task.go, reworked into TaskService

package operaton

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"sync/atomic"
	"time"
)

// Task is a locked external task as returned by fetch-and-lock.
type Task struct {
	ID                          string `json:"id"`
	TopicName                   string `json:"topicName"`
	WorkerID                    string `json:"workerId"`
	ActivityID                  string `json:"activityId"`
	ActivityInstanceID          string `json:"activityInstanceId"`
	ExecutionID                 string `json:"executionId"`
	ProcessInstanceID           string `json:"processInstanceId"`
	ProcessDefinitionID         string `json:"processDefinitionId"`
	ProcessDefinitionKey        string `json:"processDefinitionKey"`
	ProcessDefinitionVersionTag string `json:"processDefinitionVersionTag"`
	BusinessKey                 string `json:"businessKey"`
	TenantID                    string `json:"tenantId"`
	Priority                    int64  `json:"priority"`
	// Retries is the remaining retry counter; nil before the first failure.
	Retries            *int      `json:"retries"`
	ErrorMessage       string    `json:"errorMessage"`
	ErrorDetails       string    `json:"errorDetails"`
	LockExpirationTime Timestamp `json:"lockExpirationTime"`
	CreateTime         Timestamp `json:"createTime"`
	// Variables holds the variables requested by the subscription.
	Variables Variables `json:"variables"`
	// ExtensionProperties holds the BPMN extension properties when the subscription asked for them.
	ExtensionProperties map[string]string `json:"extensionProperties"`
}

// Variable returns the named variable and whether the engine sent it.
func (t *Task) Variable(name string) (Variable, bool) {
	v, ok := t.Variables[name]
	return v, ok
}

// RequireVariable returns the named variable or an error if the task has none by that name
// (not requested by the subscription, or not set in the process).
func (t *Task) RequireVariable(name string) (Variable, error) {
	v, ok := t.Variables[name]
	if !ok {
		return Variable{}, fmt.Errorf("operaton: task %s has no variable %q (requested variables: %s)", t.ID, name, t.variableNames())
	}
	return v, nil
}

func (t *Task) variableNames() string {
	if len(t.Variables) == 0 {
		return "none"
	}
	names := make([]string, 0, len(t.Variables))
	for n := range t.Variables {
		names = append(names, n)
	}
	return strings.Join(names, ", ")
}

// Handler processes one task. Return nil after completing the task through the TaskService, or
// without calling it to let the client complete the task without variables. Return a *BPMNError
// to report a BPMN error; any other error is reported as a failure and retried according to the
// retry counter (see FailureError to control it). The context is cancelled when a graceful stop
// runs out of time.
type Handler func(ctx context.Context, task *Task, svc *TaskService) error

// BPMNError is returned by a Handler to report a BPMN error (handleBpmnError) to the engine.
type BPMNError struct {
	Code      string
	Message   string
	Variables Variables
}

func (e *BPMNError) Error() string {
	if e.Message == "" {
		return "BPMN error " + e.Code
	}
	return fmt.Sprintf("BPMN error %s: %s", e.Code, e.Message)
}

// FailureError is returned by a Handler to control how a failure is reported: the remaining
// retries and the delay before the next attempt. Without it, the client counts the engine's retry
// counter down (starting at Config.Retries) and uses Config.RetryTimeout.
type FailureError struct {
	Err          error
	Retries      int
	RetryTimeout time.Duration
	// Details is the error details text; defaults to the error chain.
	Details string
}

// Fail wraps err into a FailureError with explicit retries and retry timeout.
func Fail(err error, retries int, retryTimeout time.Duration) *FailureError {
	return &FailureError{Err: err, Retries: retries, RetryTimeout: retryTimeout}
}

// NoRetry wraps err into a FailureError with zero retries, which raises an incident.
func NoRetry(err error) *FailureError { return &FailureError{Err: err} }

func (e *FailureError) Error() string {
	if e.Err == nil {
		return "task failed"
	}
	return e.Err.Error()
}

// Unwrap returns the wrapped error.
func (e *FailureError) Unwrap() error { return e.Err }

// Failure describes a failure report (handleFailure).
type Failure struct {
	Message string
	Details string
	// Retries is the remaining retry count; 0 raises an incident.
	Retries int
	// RetryTimeout is the delay until the task can be fetched again.
	RetryTimeout time.Duration
}

// ErrAlreadyHandled is returned (wrapped) when a task is completed, failed, BPMN-errored or
// unlocked a second time.
var ErrAlreadyHandled = errors.New("operaton: task already handled")

const (
	stateOpen int32 = iota
	stateCompleted
	stateFailed
	stateBPMNError
	stateUnlocked
)

var stateNames = [...]string{"open", "completed", "failed", "BPMN-errored", "unlocked"}

// TaskService reports the outcome of one task to the engine. It is bound to the task and the
// worker that locked it. Methods may be called from the handler's goroutines concurrently; only
// one of Complete, HandleFailure, HandleBPMNError and Unlock succeeds per task.
type TaskService struct {
	api      *api
	workerID string
	task     *Task
	state    atomic.Int32
	timeout  time.Duration
}

func newTaskService(a *api, workerID string, task *Task, requestTimeout time.Duration) *TaskService {
	return &TaskService{api: a, workerID: workerID, task: task, timeout: requestTimeout}
}

func (s *TaskService) call(ctx context.Context, fn func(context.Context) error) error {
	ctx, cancel := context.WithTimeout(ctx, s.timeout)
	defer cancel()
	return fn(ctx)
}

// finish runs a terminal call: it claims the terminal state first so concurrent calls cannot both
// win, and releases the claim when the engine call fails so the caller may report differently.
func (s *TaskService) finish(ctx context.Context, next int32, action string, body any) error {
	if !s.state.CompareAndSwap(stateOpen, next) {
		return fmt.Errorf("%w: cannot %s task %s, it is already %s", ErrAlreadyHandled, action, s.task.ID, stateNames[s.state.Load()])
	}
	err := s.call(ctx, func(ctx context.Context) error {
		return s.api.do(ctx, "POST", taskPath(s.task.ID, action), body, nil)
	})
	if err != nil {
		s.state.Store(stateOpen)
		return fmt.Errorf("operaton: %s task %s: %w", action, s.task.ID, err)
	}
	return nil
}

// Complete completes the task, setting the given process variables (may be nil).
func (s *TaskService) Complete(ctx context.Context, variables Variables) error {
	return s.CompleteWithLocalVariables(ctx, variables, nil)
}

// CompleteWithLocalVariables completes the task, setting process variables and variables local to
// the task's execution.
func (s *TaskService) CompleteWithLocalVariables(ctx context.Context, variables, local Variables) error {
	return s.finish(ctx, stateCompleted, "complete", map[string]any{
		"workerId":       s.workerID,
		"variables":      variables,
		"localVariables": local,
	})
}

// HandleFailure reports a failure. With Retries 0 the engine raises an incident.
func (s *TaskService) HandleFailure(ctx context.Context, f Failure) error {
	if f.Retries < 0 {
		return fmt.Errorf("operaton: Failure.Retries must not be negative, got %d", f.Retries)
	}
	return s.finish(ctx, stateFailed, "failure", map[string]any{
		"workerId":     s.workerID,
		"errorMessage": f.Message,
		"errorDetails": f.Details,
		"retries":      f.Retries,
		"retryTimeout": f.RetryTimeout.Milliseconds(),
	})
}

// HandleBPMNError reports a BPMN error with the given code; a matching error boundary or event
// subprocess in the process catches it. Variables may be nil.
func (s *TaskService) HandleBPMNError(ctx context.Context, code, message string, variables Variables) error {
	if code == "" {
		return errors.New("operaton: BPMN error code must not be empty")
	}
	return s.finish(ctx, stateBPMNError, "bpmnError", map[string]any{
		"workerId":     s.workerID,
		"errorCode":    code,
		"errorMessage": message,
		"variables":    variables,
	})
}

// ExtendLock sets the lock to expire d from now.
func (s *TaskService) ExtendLock(ctx context.Context, d time.Duration) error {
	if d <= 0 {
		return fmt.Errorf("operaton: lock extension must be positive, got %s", d)
	}
	if st := s.state.Load(); st != stateOpen {
		return fmt.Errorf("%w: cannot extend lock of task %s, it is already %s", ErrAlreadyHandled, s.task.ID, stateNames[st])
	}
	err := s.call(ctx, func(ctx context.Context) error {
		return s.api.do(ctx, "POST", taskPath(s.task.ID, "extendLock"), map[string]any{
			"workerId":    s.workerID,
			"newDuration": d.Milliseconds(),
		}, nil)
	})
	if err != nil {
		return fmt.Errorf("operaton: extend lock of task %s: %w", s.task.ID, err)
	}
	return nil
}

// Unlock releases the lock without completing the task, so any worker can fetch it again.
func (s *TaskService) Unlock(ctx context.Context) error {
	return s.finish(ctx, stateUnlocked, "unlock", nil)
}

// SetVariables sets variables on the task's process instance without completing the task.
func (s *TaskService) SetVariables(ctx context.Context, variables Variables) error {
	return s.setVariables(ctx, "/process-instance/"+pathEscape(s.task.ProcessInstanceID)+"/variables", s.task.ProcessInstanceID, variables)
}

// SetLocalVariables sets variables local to the task's execution without completing the task.
func (s *TaskService) SetLocalVariables(ctx context.Context, variables Variables) error {
	return s.setVariables(ctx, "/execution/"+pathEscape(s.task.ExecutionID)+"/localVariables", s.task.ExecutionID, variables)
}

func (s *TaskService) setVariables(ctx context.Context, path, owner string, variables Variables) error {
	if owner == "" {
		return fmt.Errorf("operaton: task %s carries no process instance/execution id to set variables on", s.task.ID)
	}
	err := s.call(ctx, func(ctx context.Context) error {
		return s.api.do(ctx, "POST", path, map[string]any{"modifications": variables}, nil)
	})
	if err != nil {
		return fmt.Errorf("operaton: set variables for task %s: %w", s.task.ID, err)
	}
	return nil
}

func (s *TaskService) handled() bool { return s.state.Load() != stateOpen }
