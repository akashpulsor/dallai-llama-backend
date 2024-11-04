package org.springframework.boot.crm.entity;

import jakarta.persistence.Embeddable;
import lombok.Data;

@Data
@Embeddable
public class BankDetails {
    private String bankAccountNumber;

    private String bankName;

    private String bankBranch;

    private String ifscCode;
}
