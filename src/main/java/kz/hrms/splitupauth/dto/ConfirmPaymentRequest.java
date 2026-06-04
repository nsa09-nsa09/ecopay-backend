package kz.hrms.splitupauth.dto;

import lombok.Data;

@Data
public class ConfirmPaymentRequest {

    /**
     * Optional gateway payment id observed on the redirect-back. The server
     * reconciles against the stored external id from the charge init, so this
     * is only a hint and may be blank.
     */
    private String externalTransactionId;
}