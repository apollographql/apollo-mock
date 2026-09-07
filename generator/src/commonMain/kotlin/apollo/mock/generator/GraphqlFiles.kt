package apollo.mock.generator

import com.apollographql.apollo.ast.GQLExecutableDefinition
import com.apollographql.apollo.ast.toGQLDocument
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString

/**
 * The text of the schema file (containing at least one type system definition) found in a
 * `graphqlDir`, plus the text of every other `.graphql`/`.graphqls` file found there.
 */
class GraphqlFiles(
    val schema: String,
    val operations: List<String>,
)

/**
 * Reads every `.graphql`/`.graphqls` file in [graphqlDir] and identifies the single schema file
 * (the file containing at least one type system definition) among them.
 */
fun loadGraphqlFiles(graphqlDir: String): GraphqlFiles {
  val dir = Path(graphqlDir)
  check(SystemFileSystem.metadataOrNull(dir)?.isDirectory == true) { "'$graphqlDir' is not a directory" }

  val files = SystemFileSystem.list(dir).filter {
    val extension = it.name.substringAfterLast('.', "")
    extension == "graphql" || extension == "graphqls"
  }
  require(files.isNotEmpty()) { "No .graphql/.graphqls files found in '$graphqlDir'" }

  val textByFile = files.associateWith { file ->
    SystemFileSystem.source(file).buffered().use { it.readString() }
  }

  val schemaFiles = textByFile.filterValues { text -> text.toGQLDocument().definitions.any { it !is GQLExecutableDefinition } }
  val schemaFile = when (schemaFiles.size) {
    0 -> error("No schema file found in '$graphqlDir': exactly one file must contain the type system definitions")
    1 -> schemaFiles.keys.single()
    else -> error(
        "Multiple schema files found in '$graphqlDir': ${schemaFiles.keys.joinToString(", ") { it.name }}. " +
            "Only a single schema file is allowed.",
    )
  }

  return GraphqlFiles(
      schema = textByFile.getValue(schemaFile),
      operations = textByFile.filterKeys { it != schemaFile }.values.toList(),
  )
}
