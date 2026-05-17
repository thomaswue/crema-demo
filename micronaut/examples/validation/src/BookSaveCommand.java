package examples.validation;

import io.micronaut.core.annotation.Introspected;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

@Introspected
public record BookSaveCommand(
        @NotBlank String title,
        @Min(1) int pages
) {
}
