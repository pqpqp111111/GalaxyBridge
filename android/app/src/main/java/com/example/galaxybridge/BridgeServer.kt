package com.example.galaxybridge

import android.util.Log
import com.example.galaxybridge.dto.SyncResponse
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.cio.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import java.time.Instant

class BridgeServer(
    private val reader: HealthConnectReader,
    private val syncState: SyncState,
    private val token: String
) {
    private var server: ApplicationEngine? = null

    fun start(port: Int = 8787) {
        server = embeddedServer(CIO, port = port) {
            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = false
                    isLenient = true
                    ignoreUnknownKeys = true
                })
            }
            routing {
                get("/health") {
                    call.respondText("ok")
                }
                get("/sync/sleep") {
                    if (!authenticate(call)) return@get
                    val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
                    handleSyncSleep(call, since)
                }
                post("/sync/ack") {
                    if (!authenticate(call)) return@post
                    val upto = call.request.queryParameters["upto"]?.toLongOrNull()
                    if (upto != null) {
                        syncState.lastSyncEpochMillis = upto
                        call.respondText("ok")
                    } else {
                        call.respond(HttpStatusCode.BadRequest, "missing upto")
                    }
                }
                get("/inventory") {
                    if (!authenticate(call)) return@get
                    val result = reader.dumpInventory()
                    call.respondText(result, ContentType.Text.Plain)
                }
            }
        }.start(wait = false)
        Log.i("BridgeServer", "Server started on port $port")
    }

    fun stop() {
        server?.stop(1000, 5000)
        server = null
        Log.i("BridgeServer", "Server stopped")
    }

    private suspend fun authenticate(call: ApplicationCall): Boolean {
        val requestToken = call.request.headers["X-Bridge-Token"]
        if (requestToken != token) {
            call.respond(HttpStatusCode.Unauthorized, "invalid token")
            return false
        }
        return true
    }

    private suspend fun handleSyncSleep(call: ApplicationCall, since: Long) {
        val start = Instant.ofEpochMilli(since)
        val end = Instant.now()
        val sleepDtos = reader.readSleepWithStages(start, end)
            .filter { it.source == SAMSUNG_HEALTH_PACKAGE }
            .filter { it.stages.isNotEmpty() }
            .map { night ->
                night.copy(
                    stages = night.stages.filter { it.end > it.start }
                )
            }

        val response = SyncResponse(
            generatedAt = System.currentTimeMillis(),
            sleep = sleepDtos
        )
        call.respond(response)
    }

    companion object {
        private const val SAMSUNG_HEALTH_PACKAGE = "com.sec.android.app.shealth"
    }
}
