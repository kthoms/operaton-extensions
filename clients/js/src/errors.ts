/** Raised for invalid usage or for failures that happen before or outside the engine's answer. */
export class ClientError extends Error {
  override name = "ClientError";
}

/** The engine answered with a non-2xx status. */
export class EngineError extends Error {
  override name = "EngineError";

  constructor(
    message: string,
    /** HTTP status code. */
    readonly status: number,
    /** The engine's exception type (for example `InvalidRequestException`), when it sent one. */
    readonly type?: string,
    /** The engine's error code, when it sent one. */
    readonly code?: number,
    /** Method and URL of the failed request. */
    readonly request?: string,
  ) {
    super(message);
  }
}

/**
 * Throw this from a handler to report a BPMN error (`errorEventDefinition`) instead of a
 * technical failure. The client calls `handleBpmnError` with the given code, message and variables.
 */
export class BpmnError extends Error {
  override name = "BpmnError";

  constructor(
    readonly code: string,
    message?: string,
    readonly variables?: Record<string, unknown>,
  ) {
    super(message ?? code);
  }
}

export const MISSING_BASE_URL = "Couldn't instantiate Client: the option `baseUrl` is required";
export const ALREADY_SUBSCRIBED = "Topic is already subscribed";
export const MISSING_HANDLER = "Couldn't subscribe: a handler function is required";
export const MISSING_TASK = "Task or task id is required";
export const MISSING_ERROR_CODE = "BPMN error requires an error code";
export const MISSING_ERROR_MESSAGE = "Failure requires an error message";
export const MISSING_DURATION = "A positive duration in milliseconds is required";
