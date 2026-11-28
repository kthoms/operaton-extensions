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

// Package operaton is an external task client for the Operaton BPM engine.
//
// A Client long-polls the engine's REST API (fetch-and-lock) for the tasks of subscribed topics,
// runs a Handler for each task and reports the outcome: complete, failure with retries, or BPMN
// error. It uses only the standard library (net/http, encoding/json, log/slog).
//
//	client, err := operaton.New(operaton.Config{BaseURL: "http://localhost:8080/engine-rest"})
//	if err != nil { ... }
//	_, err = client.Subscribe("credit-check", func(ctx context.Context, t *operaton.Task, svc *operaton.TaskService) error {
//		amount, err := t.RequireVariable("amount")
//		...
//		return svc.Complete(ctx, operaton.Variables{"approved": operaton.Boolean(true)})
//	}, operaton.WithVariables("amount"))
//	...
//	err = client.Run(ctx) // blocks; stops gracefully when ctx is cancelled
package operaton
