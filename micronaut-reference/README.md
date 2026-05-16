# Micronaut Maven reference app

This is a conventional Maven Micronaut hello-world app used as a benchmark
reference for the source launcher in `../micronaut`.

Run the regular JVM test:

```sh
mvn test
```

Compare it with the native source launcher test:

```sh
./benchmark.sh
```

The benchmark intentionally compares fresh process launches and touches each
test source before running so the Maven path includes incremental test
compilation:

- `touch ../micronaut/tests/hello/HelloControllerTest.java && ../micronaut/micronaut --test ../micronaut/examples/hello -- ../micronaut/tests/hello`
- `touch src/test/java/examples/hello/HelloControllerTest.java && mvn -q test`

Both paths run a `@MicronautTest` that injects an HTTP client and checks that
`GET /hello` returns `hello world`.

Sample result on this machine after Maven dependencies were already resolved:

```text
touch test source + source launcher test
  163.2 ms +/- 3.1 ms

touch test source + mvn -q test
  2.353 s +/- 0.038 s

source launcher test ran 14.41 +/- 0.36 times faster than mvn -q test
```
