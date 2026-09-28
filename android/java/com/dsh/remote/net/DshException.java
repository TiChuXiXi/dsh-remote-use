package com.dsh.remote.net;

import java.io.IOException;

/** dsh RPC business failure: HTTP 200 with result.ok==false. */
public class DshException extends IOException {

    private static final long serialVersionUID = 1L;

    public final String code;     // e.g. "gateway/bad-request"
    public final String detail;   // human-readable message, never null
    public final Object details;

    public DshException(String code, String detail, Object details) {
        this.code = (code == null || code.isEmpty()) ? "rpc/error" : code;
        this.detail = (detail == null || detail.isEmpty()) ? this.code : detail;
        this.details = details;
    }

    @Override
    public String getMessage() {
        if (detail != null && !detail.isEmpty() && !detail.equals(code)) {
            return code + ": " + detail;
        }
        return code;
    }
}