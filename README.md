# azir

A fake GraphQL server toolkit built as a Kotlin multiplatform project (JVM target for now):

- **`generator`**: given a GraphQL schema (SDL), generates JSON files containing fake entities. The
  fake data is produced by an LLM through [langchain4j](https://docs.langchain4j.dev/) (Anthropic by
  default, Ollama for local generation).
- **`server`**: given the JSON files produced by the generator, serves a working GraphQL server using
  [Ktor](https://ktor.io/) and [apollo-kotlin-execution](https://github.com/apollographql/apollo-kotlin-execution).

## Usage

### 1. Generate fake data

```shell
export ANTHROPIC_API_KEY=...
./gradlew :generator:runJvm --args="--schema sample/schema.graphqls --output data --count 8"
```

Or fully local with [Ollama](https://ollama.com/) (no API key needed):

```shell
ollama pull llama3.2
./gradlew :generator:runJvm --args="--schema sample/schema.graphqls --output data --provider ollama"
```

Use `--model` to pick another model and `--base-url` for a non-default Ollama endpoint.

This writes one `<TypeName>.json` file per object type in the `data/` directory. Scalar and enum
fields are filled by the LLM; fields pointing to other types are linked with `{__typename, id}`
references that the server hydrates at execution time.

### 2. Serve the fake data

```shell
./gradlew :server:runJvm --args="--schema sample/schema.graphqls --data data --port 4000"
```

Then:

- GraphQL endpoint: http://localhost:4000/graphql
- Apollo Sandbox: http://localhost:4000/

```shell
curl -s http://localhost:4000/graphql \
  -H "Content-Type: application/json" \
  -d '{"query": "{ products { name price category { name } reviews { rating author } } }"}'
```

## How the server resolves fields

The server parses the SDL at runtime and builds an `ExecutableSchema` with a single generic resolver:

- **Query root fields**: list fields return all stored entities of the field type, fields with an
  `id` argument look the entity up by id, other fields return the first entity.
- **Other fields**: values are read from the parent entity; `{__typename, id}` references are
  replaced with the full entity from the store.
- **Interfaces and unions** are resolved using the `__typename` injected when loading the data.

## Limitations (on purpose, it's a scaffold)

- Mutations and subscriptions are not implemented.
- Custom scalars have no coercing registered.
- Query root fields with arguments other than `id` ignore them.
