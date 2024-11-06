package org.springframework.boot.crm.exceptions;

public class SearchResponseException  extends RuntimeException{

    public SearchResponseException(String message, Exception ex) {
        super(message,ex);
    }
}
