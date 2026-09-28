package com.dsh.remote.net;

import java.io.IOException;

/**
 * Gateway-level failure: HTTP status + the contract's
 * {@code {"ok":false,"error":..,"message":..}} body.
 */
public class GatewayException extends IOException {

    private static final long serialVersionUID = 1L;

    public final int status;      // HTTP status (0 when unknown)
    public final String error;    // e.g. "invalid_credentials", "rate_limited", "not_configured"
    public final String detail;   // human-readable message, never null
    public final Object body;     // parsed JSON body or null

    public GatewayException(int status, String error, String detail, Object body) {
        this.status = status;
        this.error = (error == null || error.isEmpty()) ? "gateway_error" : error;
        this.detail = (detail == null || detail.isEmpty()) ? defaultDetail(status) : detail;
        this.body = body;
    }

    private static String defaultDetail(int status) {
        switch (status) {
            case 400:
                return "bad request";
            case 401:
                return "unauthorized";
            case 403:
                return "forbidden";
            case 404:
                return "not found";
            case 429:
                return "rate limited";
            case 503:
                return "gateway unavailable";
            default:
                return status > 0 ? ("gateway error (HTTP " + status + ")") : "unknown gateway error";
        }
    }

    @Override
    public String getMessage() {
        StringBuilder sb = new StringBuilder("gateway error");
        if (status > 0) {
            sb.append(" (HTTP ").append(status).append(')');
        }
        if (error != null && !error.isEmpty()) {
            sb.append(": ").append(error);
        }
        if (detail != null && !detail.isEmpty()) {
            sb.append(" - ").append(detail);
        }
        return sb.toString();
    }
}