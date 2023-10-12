package io.github.classgraph.issues.issue797;

import java.util.List;

import jakarta.validation.constraints.NotNull;

public record Bar(String baz, List<@NotNull String> value) {    
    public Bar {
        baz = "";
    }
}
