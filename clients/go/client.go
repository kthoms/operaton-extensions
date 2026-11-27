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
	"fmt"
	"log/slog"
	"sync"
)

// Errors returned by the Client lifecycle.
var (
	// ErrStopped is returned when the client was already stopped.
	ErrStopped = errors.New("operaton: client is stopped")
	// ErrAlreadyStarted is returned by Start when the client is already running.
	ErrAlreadyStarted = errors.New("operaton: client is already started")
	// ErrDuplicateTopic is returned by Subscribe for a topic that already has a subscription.
	ErrDuplicateTopic = errors.New("operaton: topic already subscribed")
)

const (
	clientNew = iota
	clientStarted
	clientStopped
)

// Client is an external task client: it long-polls the engine for tasks of its subscribed topics
// and runs the handlers. Create it with New, add subscriptions with Subscribe, then Start it (or
// use Run). A Client is safe for concurrent use.
type Client struct {
	cfg Config
	api *api
	log *slog.Logger

	mu    sync.Mutex
	state int
	subs  map[string]*subscription

	// runCtx ends polling; handlerCtx ends handlers (only when a graceful stop runs out of time).
	runCtx        context.Context
	runCancel     context.CancelFunc
	handlerCtx    context.Context
	handlerCancel context.CancelFunc
	wg            sync.WaitGroup
}

// New creates a client from the configuration. It does not contact the engine.
func New(cfg Config) (*Client, error) {
	cfg, err := cfg.withDefaults()
	if err != nil {
		return nil, err
	}
	c := &Client{
		cfg:  cfg,
		api:  newAPI(cfg),
		log:  cfg.Logger.With("component", "operaton-client", "workerId", cfg.WorkerID),
		subs: make(map[string]*subscription),
	}
	c.runCtx, c.runCancel = context.WithCancel(context.Background())
	c.handlerCtx, c.handlerCancel = context.WithCancel(context.Background())
	return c, nil
}

// WorkerID returns the worker id the client locks tasks with.
func (c *Client) WorkerID() string { return c.cfg.WorkerID }

// Subscribe registers a handler for a topic and returns the function that removes the
// subscription again. Before Start the subscription is only registered; after Start it begins
// polling immediately. Unsubscribing stops further fetching for the topic; handlers already
// running finish. A topic can have one subscription at a time.
func (c *Client) Subscribe(topic string, handler Handler, opts ...SubscribeOption) (unsubscribe func(), err error) {
	if topic == "" {
		return nil, errors.New("operaton: topic must not be empty")
	}
	if handler == nil {
		return nil, errors.New("operaton: handler must not be nil")
	}
	s := &subscription{topic: topic, handler: handler, lockDuration: c.cfg.LockDuration, concurrency: c.cfg.MaxConcurrency}
	for _, o := range opts {
		o(s)
	}
	if err := s.validate(); err != nil {
		return nil, fmt.Errorf("operaton: subscription %q: %w", topic, err)
	}

	c.mu.Lock()
	defer c.mu.Unlock()
	if c.state == clientStopped {
		return nil, ErrStopped
	}
	if _, dup := c.subs[topic]; dup {
		return nil, fmt.Errorf("%w: %q", ErrDuplicateTopic, topic)
	}
	c.subs[topic] = s
	if c.state == clientStarted {
		c.launch(s)
	}
	var once sync.Once
	return func() {
		once.Do(func() {
			c.mu.Lock()
			defer c.mu.Unlock()
			if c.subs[topic] == s {
				delete(c.subs, topic)
			}
			if s.cancel != nil {
				s.cancel()
			}
			c.log.Info("unsubscribed", "topic", topic)
		})
	}, nil
}

// Start begins polling for all subscriptions. It returns immediately.
func (c *Client) Start() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	switch c.state {
	case clientStarted:
		return ErrAlreadyStarted
	case clientStopped:
		return ErrStopped
	}
	c.state = clientStarted
	for _, s := range c.subs {
		c.launch(s)
	}
	c.log.Info("started", "baseUrl", c.cfg.BaseURL, "subscriptions", len(c.subs))
	return nil
}

// launch starts the poller of a subscription; c.mu must be held.
func (c *Client) launch(s *subscription) {
	ctx, cancel := context.WithCancel(c.runCtx)
	s.cancel = cancel
	c.wg.Add(1)
	go func() {
		defer c.wg.Done()
		c.poll(ctx, s)
	}()
}

// Stop stops fetching tasks and waits for the running handlers to finish. If ctx ends first, the
// handlers' contexts are cancelled and Stop returns an error wrapping ctx's error. Tasks that were locked but not
// yet processed stay locked until their lock expires. Stop may be called once; later calls return
// ErrStopped. A client that was never started stops immediately.
func (c *Client) Stop(ctx context.Context) error {
	c.mu.Lock()
	if c.state == clientStopped {
		c.mu.Unlock()
		return ErrStopped
	}
	c.state = clientStopped
	c.mu.Unlock()

	c.log.Info("stopping, waiting for running handlers")
	c.runCancel()
	done := make(chan struct{})
	go func() { c.wg.Wait(); close(done) }()
	select {
	case <-done:
		c.handlerCancel()
		c.log.Info("stopped")
		return nil
	case <-ctx.Done():
		c.handlerCancel()
		c.log.Warn("stop timed out, cancelled running handlers", "reason", ctx.Err())
		return fmt.Errorf("operaton: stop: handlers still running: %w", ctx.Err())
	}
}

// Run starts the client, blocks until ctx is cancelled and then stops it, waiting at most
// Config.ShutdownTimeout for running handlers. It returns the error of Start or Stop.
func (c *Client) Run(ctx context.Context) error {
	if err := c.Start(); err != nil {
		return err
	}
	<-ctx.Done()
	stopCtx, cancel := context.WithTimeout(context.Background(), c.cfg.ShutdownTimeout)
	defer cancel()
	return c.Stop(stopCtx)
}
