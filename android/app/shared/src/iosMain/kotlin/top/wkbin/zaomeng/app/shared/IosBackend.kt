package top.wkbin.zaomeng.app.shared

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import top.wkbin.zaomeng.ktor.KtorServiceGraph
import top.wkbin.zaomeng.ktor.plugins.configureObservability
import top.wkbin.zaomeng.ktor.plugins.configureSecurity
import top.wkbin.zaomeng.ktor.routes.cardRoutes
import top.wkbin.zaomeng.ktor.routes.cardsManagementRoutes
import top.wkbin.zaomeng.ktor.routes.chapterManagementRoutes
import top.wkbin.zaomeng.ktor.routes.chapterRoutes
import top.wkbin.zaomeng.ktor.routes.diagnosticsRoute
import top.wkbin.zaomeng.ktor.routes.dialogueAdvancedRoutes
import top.wkbin.zaomeng.ktor.routes.dialogueRoutes
import top.wkbin.zaomeng.ktor.routes.dialogueStreamRoutes
import top.wkbin.zaomeng.ktor.routes.healthRoute
import top.wkbin.zaomeng.ktor.routes.personaRoutes
import top.wkbin.zaomeng.ktor.routes.pluginOperationsRoutes
import top.wkbin.zaomeng.ktor.routes.pluginRoutes
import top.wkbin.zaomeng.ktor.routes.relationsRoutes
import top.wkbin.zaomeng.ktor.routes.runManagementRoutes
import top.wkbin.zaomeng.ktor.routes.runOperationsRoutes
import top.wkbin.zaomeng.ktor.routes.runsRoute
import top.wkbin.zaomeng.ktor.routes.sessionManagementRoutes
import top.wkbin.zaomeng.ktor.routes.settingsManagementRoutes
import top.wkbin.zaomeng.ktor.routes.suggestionsRoutes
import top.wkbin.zaomeng.ktor.routes.worldMemoryRoutes
import top.wkbin.zaomeng.platform.IosServerPlatform

/**
 * iOS 内嵌后端：与 desktop/android 一致，进程内启动 Ktor CIO server，
 * 数据走 Room（SQLite，ApplicationSupport/zaomeng）。启动一次，幂等。
 */
object IosBackend {
    private const val DEFAULT_PORT = 8765
    private const val DEFAULT_TOKEN = "ios-dev-token"

    @Volatile
    private var started = false

    fun start(port: Int = DEFAULT_PORT, token: String = DEFAULT_TOKEN) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }

        val services = KtorServiceGraph(IosServerPlatform())
        val server = embeddedServer(CIO, port = port, host = "127.0.0.1") {
            install(ContentNegotiation) {
                json(
                    Json {
                        prettyPrint = true
                        isLenient = true
                        ignoreUnknownKeys = true
                    },
                )
            }
            configureObservability()
            configureSecurity(token)

            val storage = services.storage
            routing {
                healthRoute()
                runsRoute(storage)
                diagnosticsRoute(storage, services.diagnostics)
                dialogueRoutes(services.dialogue, storage)
                sessionManagementRoutes(services.sessionManagement)
                runManagementRoutes(services.runManagement, services.runPackages)
                settingsManagementRoutes(services.settingsManagement)
                dialogueStreamRoutes(services.dialogueStream, storage)
                suggestionsRoutes(services.suggestions)
                dialogueAdvancedRoutes(services.dialogueAdvanced)
                chapterRoutes(services.chapter)
                chapterManagementRoutes(services.chapterManagement)
                cardRoutes(services.cards)
                cardsManagementRoutes(services.cardsManagement)
                personaRoutes(services.persona)
                relationsRoutes(services.relations)
                runOperationsRoutes(services.runOperations)
                worldMemoryRoutes(services.worldMemory)
                pluginRoutes(services.plugins)
                pluginOperationsRoutes(services.pluginOperations)
            }
        }
        server.start(wait = false)
    }
}
