package com.dalaillama.content.exception;

public class SearchResponseException  extends RuntimeException{

    public SearchResponseException(String message, Exception ex) {
        super(message,ex);
    }
}
