package apollo.mock.executableschema

import com.apollographql.apollo.api.ExecutionContext

/**
 * The name of the operation currently being executed, threaded through [ExecutionContext] so that
 * [FakeDataResolver] can look up its per-operation `@mock` values regardless of nesting depth.
 */
class CurrentOperationName(val name: String?) : ExecutionContext.Element {
  override val key: ExecutionContext.Key<*>
    get() = Key

  companion object Key : ExecutionContext.Key<CurrentOperationName>
}
