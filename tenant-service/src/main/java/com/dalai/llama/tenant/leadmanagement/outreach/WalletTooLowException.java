package com.dalai.llama.tenant.leadmanagement.outreach;

/** Billing refused an add-on because the wallet can't cover it: 402, top up first. */
public class WalletTooLowException extends RuntimeException {

    public WalletTooLowException() {
        super("Your wallet balance is too low for this. Top up your wallet and try again.");
    }
}
