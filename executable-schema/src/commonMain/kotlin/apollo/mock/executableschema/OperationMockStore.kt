package apollo.mock.executableschema

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.json.Json

/**
 * Holds the per-operation mocks loaded from the JSON files written by the generator's `@mock`
 * directive processing: one `operation/<operationName>.json` file per operation, mapping the
 * GraphQL path in the document (the empty string for the operation root) to the mocked value.
 */
class OperationMockStore(
    private val operationMocks: Map<String, Map<String, Any?>>,
) {
  /**
   * Returns the path-to-value mocks for [operationName], or `null` if that operation has no
   * mocks (or [operationName] itself is `null`, e.g. it couldn't be determined from the request).
   */
  fun forOperation(operationName: String?): Map<String, Any?>? {
    return operationName?.let { operationMocks[it] }
  }

  companion object {
    fun load(dir: Path): OperationMockStore {
      val operationDir = Path(dir, "operation")
      if (SystemFileSystem.metadataOrNull(operationDir)?.isDirectory != true) {
        return OperationMockStore(emptyMap())
      }

      val operationMocks = SystemFileSystem.list(operationDir)
          .filter { it.name.substringAfterLast('.', "") == "json" }
          .associate { file ->
            val operationName = file.name.substringBeforeLast('.')
            val text = SystemFileSystem.source(file).buffered().use { it.readString() }
            @Suppress("UNCHECKED_CAST")
            val mocks = Json.parseToJsonElement(text).toKotlin() as Map<String, Any?>
            operationName to mocks
          }
      println("Loaded mocks for ${operationMocks.size} operation(s) from $operationDir")
      return OperationMockStore(operationMocks)
    }
  }
}
