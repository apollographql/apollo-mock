package apollo.mock.generator

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.curl.Curl

internal actual fun httpClientEngineFactory(): HttpClientEngineFactory<*> = Curl
