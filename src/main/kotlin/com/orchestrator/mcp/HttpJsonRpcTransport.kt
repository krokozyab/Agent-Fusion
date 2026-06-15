package com.orchestrator.mcp

import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.*
import kotlinx.serialization.Serializable

/**
 * HTTP JSON-RPC 2.0 Transport Handler
 *
 * Handles standard JSON-RPC 2.0 requests and notifications according to the MCP protocol.
 * Designed for Claude Desktop and other standard MCP clients.
 *
 * Supports:
 * - Requests (with id): initialize, tools/list, tools/call, resources/list, resources/read
 * - Notifications (without id): notifications/initialized
 *
 * Returns null for notifications (no response should be sent).
 * Returns JsonElement for requests (response should be sent).
 */
object HttpJsonRpcTransport {

    /**
     * Process a JSON-RPC 2.0 request or notification
     * Returns null for notifications (no response), JsonElement for requests (send response)
     */
    fun handleRequest(
        requestJson: JsonElement,
        mcpServer: McpServerImpl
    ): JsonElement? {
        // Parsing failures are genuine -32700 Parse errors with a null id (the id is unknown).
        val request = try {
            parseJsonRpcRequest(requestJson)
        } catch (e: Exception) {
            return buildJsonErrorResponse(
                id = null,
                code = -32700,
                message = "Parse error: ${e.message ?: e::class.simpleName}"
            )
        }

        val isNotification = request.id == null

        // Dispatch failures, by contrast, are -32603 Internal errors and must carry the request id
        // so the client can correlate the response with its request.
        return try {
            val result = when (request.method) {
                // MCP Requests (require responses)
                "initialize" -> handleInitialize(request, mcpServer)
                "tools/list" -> handleToolsList(request, mcpServer)
                "tools/call" -> handleToolsCall(request, mcpServer)
                "resources/list" -> handleResourcesList(request, mcpServer)
                "resources/read" -> handleResourcesRead(request, mcpServer)

                // MCP Notifications (fire-and-forget, no response)
                "notifications/initialized" -> return null

                else -> {
                    if (isNotification) return null
                    JsonRpcError(code = -32601, message = "Method not found: ${request.method}")
                }
            }

            if (isNotification) null else buildJsonResponse(request.id, result)
        } catch (e: Exception) {
            if (isNotification) {
                null
            } else {
                buildJsonErrorResponse(
                    id = request.id,
                    code = -32603,
                    message = "Internal error: ${e.message ?: e::class.simpleName}"
                )
            }
        }
    }

    // ---- Request Handlers ----

    private fun handleInitialize(
        request: JsonRpcRequest,
        mcpServer: McpServerImpl
    ): Any {
        return buildJsonObject {
            put("protocolVersion", JsonPrimitive("2024-11-05"))
            put("serverInfo", buildJsonObject {
                put("name", JsonPrimitive("Orchestrator MCP Server"))
                put("version", JsonPrimitive("1.0.0"))
            })
            put("capabilities", buildJsonObject {
                put("tools", buildJsonObject {
                    put("listChanged", JsonPrimitive(false))
                })
                put("resources", buildJsonObject {
                    put("subscribe", JsonPrimitive(false))
                })
            })
        }
    }

    private fun handleToolsList(
        request: JsonRpcRequest,
        mcpServer: McpServerImpl
    ): Any {
        val tools = mcpServer.tools()
        return buildJsonObject {
            put("tools", JsonArray(tools.map { tool ->
                buildJsonObject {
                    put("name", JsonPrimitive(tool.name))
                    put("description", JsonPrimitive(tool.description))
                    put("inputSchema", Json.parseToJsonElement(tool.jsonSchema.toString()))
                }
            }))
        }
    }

    private fun handleToolsCall(
        request: JsonRpcRequest,
        mcpServer: McpServerImpl
    ): Any {
        val params = request.params as? JsonObject
            ?: return JsonRpcError(code = -32602, message = "Invalid params: expected object")

        val toolName = params["name"]?.jsonPrimitive?.contentOrNull
            ?: return JsonRpcError(code = -32602, message = "Missing 'name' parameter")

        // Handle both formats:
        // 1. {"name": "...", "arguments": {...}} - standard format
        // 2. {"name": "...", ...other params...} - Claude Desktop format
        val toolArgs = if (params.containsKey("arguments")) {
            params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        } else {
            // Extract all params except "name" as tool arguments
            buildJsonObject {
                params.forEach { (key, value) ->
                    if (key != "name") {
                        put(key, value)
                    }
                }
            }
        }

        return try {
            val result = mcpServer.executeTool(toolName, toolArgs)
            val toolResult = mcpServer.toolResultToJson(result)
            // Wrap tool result in MCP content format for Claude Desktop compatibility
            buildJsonObject {
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", JsonPrimitive("text"))
                        put("text", JsonPrimitive(toolResult.toString()))
                    })
                })
            }
        } catch (e: Exception) {
            JsonRpcError(
                code = -32000,
                message = "Tool execution failed: ${e.message ?: e::class.simpleName}"
            )
        }
    }

    private fun handleResourcesList(
        request: JsonRpcRequest,
        mcpServer: McpServerImpl
    ): Any {
        return buildJsonObject {
            put("resources", JsonArray(listOf(
                buildJsonObject {
                    put("uri", JsonPrimitive("tasks://"))
                    put("name", JsonPrimitive("Tasks"))
                    put("description", JsonPrimitive("Task listing and filtering"))
                    put("mimeType", JsonPrimitive("application/json"))
                },
                buildJsonObject {
                    put("uri", JsonPrimitive("metrics://"))
                    put("name", JsonPrimitive("Metrics"))
                    put("description", JsonPrimitive("Aggregated metrics and series"))
                    put("mimeType", JsonPrimitive("application/json"))
                }
            )))
        }
    }

    private fun handleResourcesRead(
        request: JsonRpcRequest,
        mcpServer: McpServerImpl
    ): Any {
        val params = request.params as? JsonObject
            ?: return JsonRpcError(code = -32602, message = "Invalid params")

        val uri = params["uri"]?.jsonPrimitive?.contentOrNull
            ?: return JsonRpcError(code = -32602, message = "Missing 'uri' parameter")

        return try {
            val body = mcpServer.fetchResourceBody(uri)
            buildJsonObject {
                put("contents", JsonArray(listOf(
                    buildJsonObject {
                        put("uri", JsonPrimitive(uri))
                        put("mimeType", JsonPrimitive("application/json"))
                        put("text", JsonPrimitive(body))
                    }
                )))
            }
        } catch (e: Exception) {
            JsonRpcError(
                code = -32000,
                message = "Resource read failed: ${e.message ?: e::class.simpleName}"
            )
        }
    }

    // ---- Helper Classes & Functions ----

    data class JsonRpcRequest(
        val jsonrpc: String = "2.0",
        // Raw id element so the response can echo it with the SAME JSON type (a numeric id:1 must
        // come back as 1, not "1"). Null means the id was absent → notification.
        val id: JsonElement?,
        val method: String,
        val params: Any? = null
    )

    data class JsonRpcError(
        val code: Int,
        val message: String
    )

    private fun parseJsonRpcRequest(element: JsonElement): JsonRpcRequest {
        val obj = element as? JsonObject
            ?: throw IllegalArgumentException("Request must be a JSON object")

        val jsonrpc = obj["jsonrpc"]?.jsonPrimitive?.contentOrNull ?: "2.0"
        if (jsonrpc != "2.0") throw IllegalArgumentException("Invalid jsonrpc version")

        // Preserve the id as-is (string/number/null per spec). Absent id (Kotlin null) = notification;
        // an explicit JsonNull is kept so it round-trips correctly.
        val id = obj["id"]

        val method = obj["method"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("Missing 'method' field")

        val params = obj["params"]

        return JsonRpcRequest(
            jsonrpc = jsonrpc,
            id = id,
            method = method,
            params = params
        )
    }

    private fun buildJsonResponse(id: JsonElement?, result: Any?): JsonElement {
        return when (result) {
            is JsonElement -> buildJsonObject {
                put("jsonrpc", JsonPrimitive("2.0"))
                put("id", id ?: JsonNull)
                put("result", result)
            }
            is JsonRpcError -> buildJsonErrorResponse(id, result.code, result.message)
            else -> buildJsonObject {
                put("jsonrpc", JsonPrimitive("2.0"))
                put("id", id ?: JsonNull)
                put("result", Json.encodeToJsonElement(result))
            }
        }
    }

    private fun buildJsonErrorResponse(id: JsonElement?, code: Int, message: String): JsonElement {
        return buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("id", id ?: JsonNull)
            put("error", buildJsonObject {
                put("code", JsonPrimitive(code))
                put("message", JsonPrimitive(message))
            })
        }
    }
}
