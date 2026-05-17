# Micronaut guide examples

This directory contains guide-inspired source applications that run directly
through the `micronaut` source launcher. Each guide is a plain directory with
`src` and `test` subdirectories rather than a Maven or Gradle project.

The Micronaut guides index was reviewed on 2026-05-17. The website feed
currently lists 173 guide entries. The examples here start with guide areas that
fit the current launcher: controllers, Micronaut Test, dependency injection,
configuration properties, validation, HTTP clients, static resources, and
Micronaut Data JDBC with SQLite.

The website guide list can be refreshed with:

```sh
curl -fsSL https://guides.micronaut.io/latest/feed.json \
  | jq -r '.items[] | "- [ ] `" + .id + "` - " + .title'
```

## Current examples

| Directory | Guide area | Run |
| --- | --- | --- |
| `creating-first-app` | Getting Started | `./micronaut --test --port 0 guides/creating-first-app/src -- guides/creating-first-app/test` |
| `dependency-injection` | Core Basics / DI | `./micronaut --test --port 0 guides/dependency-injection/src -- guides/dependency-injection/test` |
| `configuration-properties` | Core Basics / Configuration | `./micronaut --test --port 0 guides/configuration-properties/src -- guides/configuration-properties/test` |
| `validation` | Validation | `./micronaut --test --port 0 guides/validation/src -- guides/validation/test` |
| `http-client` | HTTP Client | `./micronaut --test --port 0 guides/http-client/src -- guides/http-client/test` |
| `static-resources` | Server-Side HTML / Static Resources | `./micronaut --test --port 0 guides/static-resources/src -- guides/static-resources/test` |
| `data-jdbc-sqlite` | Data JDBC | `./micronaut --test --port 0 guides/data-jdbc-sqlite/src -- guides/data-jdbc-sqlite/test` |

## Website guide IDs covered or partially covered

- [x] `creating-your-first-micronaut-app` - mapped by `creating-first-app`
- [x] `micronaut-dependency-injection-types` - mapped by `dependency-injection`
- [x] `micronaut-configuration` - mapped by `configuration-properties`
- [x] `micronaut-custom-validation-annotation` / validation basics - mapped by `validation`
- [x] `micronaut-http-client` - mapped by `http-client`
- [x] `micronaut-static-resources` - mapped by `static-resources`
- [x] `micronaut-data-jdbc-repository` - mapped by `data-jdbc-sqlite`

## Guide coverage backlog

Good next candidates:

- `micronaut-error-handling`
- `micronaut-cors`
- `micronaut-content-negotiation`
- `micronaut-server-filter-request`
- HTTP server health and CORS, once management/security module dependencies are
  exercised through the resolver.
- `micronaut-websocket`, because the launcher already bakes in Micronaut
  WebSocket jars.
- `micronaut-scheduled` and `micronaut-cache`, once their modules are added to
  the dependency graph or
  launcher image.
- `micronaut-jaxrs-jdbc`, `micronaut-openapi-*`, `micronaut-graphql`,
  `micronaut-produces-xml`, `micronaut-openpdf`, and `micronaut-views-thymeleaf`,
  which need additional annotation processors or libraries.

Lower priority for this demo:

- `micronaut-aws-*`, `micronaut-azure-*`, `micronaut-google-*`,
  `micronaut-oracle-*`, `micronaut-k8s*`, `micronaut-kafka`,
  `micronaut-rabbitmq*`, `micronaut-mqtt`, `micronaut-object-storage-*`,
  `micronaut-security-*`, tracing, registry, and Testcontainers guides. Those
  are important for general Micronaut compatibility but require external
  services, credentials, containers, or much larger dependency sets.
