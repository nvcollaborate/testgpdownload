package com.testappnongpdownload.app;

public class GoogleAuthenticationException extends Exception {

    public GoogleAuthenticationException(String msg) {
        super(msg);
    }

    public GoogleAuthenticationException(String msg, Throwable cause) {
        super(msg, cause);
    }
}
