import axios, { AxiosError } from 'axios';
import * as readline from 'readline';

const MCP_ENDPOINT = process.env.MCP_ENDPOINT || 'http://127.0.0.1:3000/mcp/json-rpc';
const MAX_RETRIES = 3;
const RETRY_DELAY_MS = 1000;

interface JsonRpcRequest {
  jsonrpc: '2.0';
  id?: string | number;
  method: string;
  params?: any;
}

interface JsonRpcResponse {
  jsonrpc: '2.0';
  id?: string | number;
  result?: any;
  error?: {
    code: number;
    message: string;
    data?: any;
  };
}

// Configure axios with longer timeout
const axiosInstance = axios.create({
  timeout: 60000, // 60 second timeout
  maxRedirects: 5,
  validateStatus: () => true, // Don't throw on any status code
});

async function forwardRequest(request: JsonRpcRequest): Promise<JsonRpcResponse | null> {
  let lastError: Error | null = null;

  for (let attempt = 1; attempt <= MAX_RETRIES; attempt++) {
    try {
      const response = await axiosInstance.post(MCP_ENDPOINT, request, {
        headers: {
          'Content-Type': 'application/json',
        },
      });

      // Handle different response statuses
      if (response.status === 204) {
        // No Content - notification was processed, don't send response
        return null;
      }

      if (response.status === 200) {
        // Successful response
        return response.data as JsonRpcResponse;
      }

      // For non-200/204 responses, treat as error
      const errorResponse: JsonRpcResponse = {
        jsonrpc: '2.0',
        ...(request.id !== undefined && { id: request.id }),
        error: {
          code: -32000,
          message: `HTTP ${response.status}: ${response.statusText || 'Unknown error'}`,
          data: response.data,
        },
      };
      return errorResponse;
    } catch (error) {
      lastError = error instanceof Error ? error : new Error(String(error));

      // Check if it's a connection refused error
      if (
        error instanceof AxiosError &&
        (error.code === 'ECONNREFUSED' || error.code === 'ECONNRESET')
      ) {
        if (attempt < MAX_RETRIES) {
          const delay = RETRY_DELAY_MS * Math.pow(2, attempt - 1); // Exponential backoff
          console.error(`[Attempt ${attempt}/${MAX_RETRIES}] Connection failed, retrying in ${delay}ms...`, {
            endpoint: MCP_ENDPOINT,
            error: error.message,
          });
          await sleep(delay);
          continue;
        }
      }

      // Other errors - don't retry
      break;
    }
  }

  // All retries failed
  const errorResponse: JsonRpcResponse = {
    jsonrpc: '2.0',
    ...(request.id !== undefined && { id: request.id }),
    error: {
      code: -32603,
      message: `Failed to connect to MCP endpoint: ${lastError?.message || 'Unknown error'}`,
      data: {
        endpoint: MCP_ENDPOINT,
        attempts: MAX_RETRIES,
      },
    },
  };
  return errorResponse;
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function processLine(line: string): Promise<void> {
  if (!line.trim()) {
    return; // Skip empty lines
  }

  try {
    const request = JSON.parse(line) as JsonRpcRequest;

    // Validate basic JSON-RPC structure
    if (!request.jsonrpc || request.jsonrpc !== '2.0') {
      const response: JsonRpcResponse = {
        jsonrpc: '2.0',
        ...(request.id !== undefined && { id: request.id }),
        error: {
          code: -32600,
          message: 'Invalid JSON-RPC version',
        },
      };
      console.log(JSON.stringify(response));
      return;
    }

    if (!request.method) {
      const response: JsonRpcResponse = {
        jsonrpc: '2.0',
        ...(request.id !== undefined && { id: request.id }),
        error: {
          code: -32600,
          message: 'Missing method field',
        },
      };
      console.log(JSON.stringify(response));
      return;
    }

    // Forward to MCP endpoint
    const response = await forwardRequest(request);

    // Only send response if there is one (notifications may return null)
    if (response !== null) {
      console.log(JSON.stringify(response));
    }
  } catch (error) {
    const message =
      error instanceof SyntaxError
        ? `Invalid JSON: ${error.message}`
        : `Processing error: ${error instanceof Error ? error.message : String(error)}`;

    const response: JsonRpcResponse = {
      jsonrpc: '2.0',
      error: {
        code: -32700,
        message,
      },
    };
    console.log(JSON.stringify(response));
  }
}

async function main(): Promise<void> {
  const rl = readline.createInterface({
    input: process.stdin,
    output: process.stdout,
    terminal: false,
  });

  console.error(`[MCP Proxy] Starting, forwarding to ${MCP_ENDPOINT}`);
  console.error('[MCP Proxy] Waiting for JSON-RPC messages from stdin...');

  for await (const line of rl) {
    await processLine(line);
  }

  console.error('[MCP Proxy] stdin closed, exiting');
  rl.close();
}

main().catch((error) => {
  console.error('[MCP Proxy Fatal Error]', error);
  process.exit(1);
});
