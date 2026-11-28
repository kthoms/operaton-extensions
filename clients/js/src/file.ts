/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. Camunda licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Ported from https://github.com/camunda/camunda-external-task-client-js (Apache-2.0).
 * Modified for Operaton: rewritten in TypeScript on top of fetch; Copyright 2026 the Operaton
 * Extensions contributors.
 */
import { readFile } from "node:fs/promises";
import path from "node:path";

import { ClientError } from "./errors.js";

export interface FileOptions {
  filename?: string;
  mimeType?: string;
  encoding?: string;
}

/**
 * A file variable. Create one with {@link OperatonFile.fromBuffer} or
 * {@link OperatonFile.fromPath}; files received from the engine are loaded lazily with
 * {@link OperatonFile.load}.
 */
export class OperatonFile {
  filename: string;
  mimeType?: string;
  encoding?: string;
  #content: Uint8Array | undefined;
  readonly #loader: (() => Promise<Uint8Array>) | undefined;

  private constructor(
    filename: string,
    content: Uint8Array | undefined,
    loader: (() => Promise<Uint8Array>) | undefined,
    options: FileOptions,
  ) {
    this.filename = filename;
    this.#content = content;
    this.#loader = loader;
    if (options.mimeType !== undefined) this.mimeType = options.mimeType;
    if (options.encoding !== undefined) this.encoding = options.encoding;
  }

  /** A file with the given content; `filename` is required. */
  static fromBuffer(
    content: Uint8Array | string,
    options: FileOptions & { filename: string },
  ): OperatonFile {
    const bytes = typeof content === "string" ? Buffer.from(content, "utf-8") : content;
    return new OperatonFile(options.filename, bytes, undefined, options);
  }

  /** Reads a local file; the filename defaults to its base name. */
  static async fromPath(localPath: string, options: FileOptions = {}): Promise<OperatonFile> {
    const content = await readFile(localPath);
    return new OperatonFile(
      options.filename ?? path.basename(localPath),
      content,
      undefined,
      options,
    );
  }

  /** @internal A file whose content is fetched on demand. */
  static remote(
    filename: string,
    loader: () => Promise<Uint8Array>,
    options: FileOptions = {},
  ): OperatonFile {
    return new OperatonFile(filename, undefined, loader, options);
  }

  /** Whether the content is available without calling {@link load}. */
  get isLoaded(): boolean {
    return this.#content !== undefined;
  }

  /** The content. Throws when a remote file has not been loaded yet. */
  get content(): Uint8Array {
    if (this.#content === undefined) {
      throw new ClientError(`File '${this.filename}' is not loaded; call load() first`);
    }
    return this.#content;
  }

  /** Fetches the content of a remote file (a no-op for a file that already has its content). */
  async load(): Promise<this> {
    if (this.#content === undefined) {
      if (!this.#loader) {
        throw new ClientError(
          `File '${this.filename}' has no content and no source to load it from`,
        );
      }
      this.#content = await this.#loader();
    }
    return this;
  }
}
