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

// Command example is a credit-check worker for Operaton: it subscribes to the external task topic
// "credit-check", looks the customer up at a (simulated) credit bureau and decides whether a loan
// is granted. See README.md.
//
//	go run ./example deploy                          deploy credit-check.bpmn
//	go run ./example start alice 5000                start a process instance
//	go run ./example worker                          run the worker (Ctrl-C stops it gracefully)
package main

import (
	"bytes"
	"context"
	_ "embed"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"mime/multipart"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	operaton "github.com/kthoms/operaton-extensions/clients/go"
)

//go:embed credit-check.bpmn
var creditCheckBPMN []byte

func main() {
	if len(os.Args) < 2 {
		usage()
	}
	var err error
	switch os.Args[1] {
	case "deploy":
		err = deploy(engineURL())
	case "start":
		if len(os.Args) != 4 {
			usage()
		}
		err = start(engineURL(), os.Args[2], os.Args[3])
	case "worker":
		err = runWorker()
	default:
		usage()
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
}

func usage() {
	fmt.Fprintln(os.Stderr, "usage: example deploy | start <customerId> <amount> | worker\n"+
		"environment: OPERATON_URL (default "+operaton.DefaultBaseURL+"), OPERATON_USER, OPERATON_PASSWORD")
	os.Exit(2)
}

func engineURL() string {
	if u := os.Getenv("OPERATON_URL"); u != "" {
		return u
	}
	return operaton.DefaultBaseURL
}

// bureau is the simulated credit bureau: customer id to credit score.
var bureau = map[string]int{"alice": 780, "bob": 540, "carol": 690, "flaky": 700}

// maxLoan is the highest loan granted for a score; scores up to 600 get nothing.
func maxLoan(score int) int64 {
	if score <= 600 {
		return 0
	}
	return int64(score-600) * 500
}

func runWorker() error {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	client, err := operaton.New(operaton.Config{
		BaseURL:      engineURL(),
		Username:     os.Getenv("OPERATON_USER"),
		Password:     os.Getenv("OPERATON_PASSWORD"),
		LockDuration: time.Minute,
		// Retried tasks are picked up by the next poll; keep the poll short so the demo is quick.
		AsyncResponseTimeout: 10 * time.Second,
		Retries:      3,
		RetryTimeout: 2 * time.Second,
		Logger:       slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelInfo})),
	})
	if err != nil {
		return err
	}
	_, err = client.Subscribe("credit-check", checkCredit,
		operaton.WithVariables("customerId", "amount"),
		operaton.WithProcessDefinitionKey("credit-check"))
	if err != nil {
		return err
	}
	slog.Info("credit-check worker running, Ctrl-C stops it", "engine", engineURL())
	return client.Run(ctx)
}

// checkCredit is the handler of the topic "credit-check".
func checkCredit(ctx context.Context, task *operaton.Task, svc *operaton.TaskService) error {
	customerVar, err := task.RequireVariable("customerId")
	if err != nil {
		return operaton.NoRetry(err) // a model error: retrying cannot help
	}
	customer, err := customerVar.AsString()
	if err != nil {
		return operaton.NoRetry(err)
	}
	amountVar, err := task.RequireVariable("amount")
	if err != nil {
		return operaton.NoRetry(err)
	}
	amount, err := amountVar.AsInt64()
	if err != nil {
		return operaton.NoRetry(err)
	}

	score, ok := bureau[customer]
	if !ok {
		// Business outcome the process models with a boundary error event.
		return &operaton.BPMNError{
			Code:      "UNKNOWN_CUSTOMER",
			Message:   "no credit record for " + customer,
			Variables: operaton.Variables{"reason": operaton.String("unknown customer " + customer)},
		}
	}
	if customer == "flaky" && (task.Retries == nil || *task.Retries > 1) {
		// A transient bureau outage: an ordinary error is retried after Config.RetryTimeout.
		return errors.New("credit bureau temporarily unavailable")
	}

	limit := maxLoan(score)
	approved := amount <= limit
	slog.Info("credit checked", "customer", customer, "score", score, "amount", amount, "limit", limit, "approved", approved)
	return svc.Complete(ctx, operaton.Variables{
		"score":     operaton.Integer(int32(score)),
		"limit":     operaton.Long(limit),
		"approved":  operaton.Boolean(approved),
		"checkedAt": operaton.Date(time.Now()),
	})
}

func deploy(base string) error {
	var buf bytes.Buffer
	mw := multipart.NewWriter(&buf)
	_ = mw.WriteField("deployment-name", "credit-check")
	_ = mw.WriteField("enable-duplicate-filtering", "true")
	fw, err := mw.CreateFormFile("data", "credit-check.bpmn")
	if err != nil {
		return err
	}
	if _, err := fw.Write(creditCheckBPMN); err != nil {
		return err
	}
	if err := mw.Close(); err != nil {
		return err
	}
	var out struct {
		ID string `json:"id"`
	}
	if err := post(base+"/deployment/create", mw.FormDataContentType(), &buf, &out); err != nil {
		return err
	}
	fmt.Println("deployed credit-check, deployment", out.ID)
	return nil
}

func start(base, customer, amount string) error {
	n, err := strconv.ParseInt(amount, 10, 64)
	if err != nil {
		return fmt.Errorf("amount %q is not a whole number: %w", amount, err)
	}
	body, err := json.Marshal(map[string]any{
		"businessKey": customer + "-" + strconv.FormatInt(time.Now().Unix(), 10),
		"variables": operaton.Variables{
			"customerId": operaton.String(customer),
			"amount":     operaton.Long(n),
		},
	})
	if err != nil {
		return err
	}
	var out struct {
		ID string `json:"id"`
	}
	if err := post(base+"/process-definition/key/credit-check/start", "application/json", bytes.NewReader(body), &out); err != nil {
		return err
	}
	fmt.Println("started process instance", out.ID)
	fmt.Printf("result: %s/history/process-instance/%s (state) and %s/history/variable-instance?processInstanceId=%s\n", base, out.ID, base, out.ID)
	return nil
}

func post(url, contentType string, body io.Reader, out any) error {
	req, err := http.NewRequest(http.MethodPost, url, body)
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", contentType)
	if u := os.Getenv("OPERATON_USER"); u != "" {
		req.SetBasicAuth(u, os.Getenv("OPERATON_PASSWORD"))
	}
	resp, err := (&http.Client{Timeout: 30 * time.Second}).Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(resp.Body)
	if resp.StatusCode/100 != 2 {
		return fmt.Errorf("POST %s: HTTP %d: %s", url, resp.StatusCode, data)
	}
	return json.Unmarshal(data, out)
}
