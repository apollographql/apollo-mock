# Apollo Mock 

Apollo Mock is a framework to generate and serve mock data for your testing or experimentation needs.

Apollo Mock uses Large Language Models to generate realistic user data.

Apollo Mock has two sides:

- **`generator`**: given a GraphQL schema (SDL), generates JSON files containing fake entities. 
- **`server`**: given the JSON files produced by the generator, serves a working GraphQL server.

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
- Sandbox: http://localhost:4000/

```shell
curl -s http://localhost:4000/graphql \
  -H "Content-Type: application/json" \
  -d '{"query": "{ products { name price category { name } reviews { rating author } } }"}'
```
