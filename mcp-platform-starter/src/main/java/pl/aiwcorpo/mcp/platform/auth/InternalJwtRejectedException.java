package pl.aiwcorpo.mcp.platform.auth;

/** The internal JWT of a request was refused. The message is the reason code only, never token content. */
public class InternalJwtRejectedException extends Exception {

    private final InternalJwtRejection reason;

    public InternalJwtRejectedException(InternalJwtRejection reason) {
        super(reason.code(), null, false, false);
        this.reason = reason;
    }

    public InternalJwtRejectedException(InternalJwtRejection reason, Throwable cause) {
        super(reason.code(), cause, false, false);
        this.reason = reason;
    }

    public InternalJwtRejection reason() {
        return reason;
    }
}
