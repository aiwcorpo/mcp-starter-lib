package pl.aiwcorpo.mcp.platform.error;

import java.util.Arrays;
import java.util.Optional;

/** Error codes a client may see in a tool result (C10, {@code schemas/tool-error.schema.json}). */
public enum ToolErrorCode {

    UNAUTHENTICATED("unauthenticated"),
    ACCESS_DENIED("access_denied"),
    TOOL_DISABLED("tool_disabled"),
    CONFIRMATION_REQUIRED("confirmation_required"),
    CONFIRMATION_FAILED("confirmation_failed"),
    CONFIRMATION_DECLINED("confirmation_declined"),
    CREDENTIAL_REQUIRED("credential_required"),
    NOT_FOUND_OR_DENIED("not_found_or_denied"),
    INVALID_ARGUMENT("invalid_argument"),
    RATE_LIMITED("rate_limited"),
    STEP_UP_REQUIRED("step_up_required"),
    UPSTREAM_UNAVAILABLE("upstream_unavailable"),
    INTERNAL_ERROR("internal_error");

    private final String code;

    ToolErrorCode(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Optional<ToolErrorCode> of(String code) {
        return Arrays.stream(values()).filter(c -> c.code.equals(code)).findFirst();
    }
}
