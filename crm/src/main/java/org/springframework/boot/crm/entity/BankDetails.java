package org.springframework.boot.crm.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Data;

@Data
@Embeddable
public class BankDetails {

    @Column(name="account_number")
    private String accountNumber;

    @Column(name="account_holder_name")
    private String accountHolderName;

    @Column(name="bank_name")
    private String bankName;

    @Column(name="bank_branch")
    private String bankBranch;

    @Column(name="ifsc_code")
    private String ifscCode;
}
