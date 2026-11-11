package com.danilocordeiro.sandboxrunner.spi;

import java.nio.file.Path;

/** A host directory or file visible inside the sandbox. */
public record Mount(Path hostPath, String sandboxPath, boolean readOnly) {
}
