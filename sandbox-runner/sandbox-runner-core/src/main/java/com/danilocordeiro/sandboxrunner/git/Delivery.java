package com.danilocordeiro.sandboxrunner.git;

import com.danilocordeiro.sandboxrunner.RunStatus;
import java.util.Optional;

/** How the result reached (or did not reach) the host. */
public record Delivery(RunStatus status, Optional<String> branch, String detail) {
}
