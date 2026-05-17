package guides.dependencyinjection;

import jakarta.inject.Singleton;

@Singleton
public final class GreetingService {
    String greet(String name) {
        return "hello " + name;
    }
}
