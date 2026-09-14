package apollo.mock.generator

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.cio.CIO

internal actual fun httpClientEngineFactory(): HttpClientEngineFactory<*> = CIO
