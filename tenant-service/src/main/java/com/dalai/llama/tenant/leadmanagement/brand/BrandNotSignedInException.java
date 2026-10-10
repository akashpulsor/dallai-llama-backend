package com.dalai.llama.tenant.leadmanagement.brand;

/** A brand action (follow, request a video, brand home) without a valid brand session: 401, and
 * the public page shows the sign-in form. */
public class BrandNotSignedInException extends RuntimeException {

    public BrandNotSignedInException() {
        super("Sign in with your work email to continue");
    }
}
