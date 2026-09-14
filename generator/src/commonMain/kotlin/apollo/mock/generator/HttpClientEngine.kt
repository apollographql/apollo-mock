package apollo.mock.generator

import io.ktor.client.engine.HttpClientEngineFactory

/**
 * CIO's TLS implementation only exists on the JVM (Kotlin/Native throws "TLS sessions are not
 * supported on Native platform" as soon as an `https://` request is made), so each platform picks
 * an engine that can actually talk TLS.
 */
internal expect fun httpClientEngineFactory(): HttpClientEngineFactory<*>
