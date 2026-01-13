package com.dalai.llama.billing.dto.mapper;

import com.dalai.llama.billing.domain.entity.*;
import com.dalai.llama.billing.dto.response.*;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface BillingMapper {

    @Mapping(target = "walletId", source = "id")
    WalletResponse toWalletResponse(Wallet wallet);

    BillingStateResponse toBillingStateResponse(BillingState state);

    @Mapping(target = "type", expression = "java(transaction.getType().name())")
    TransactionResponse toTransactionResponse(Transaction transaction);

    @Mapping(target = "paymentId", source = "id")
    @Mapping(target = "status", expression = "java(payment.getStatus().name())")
    PaymentResponse toPaymentResponse(Payment payment);

    RatePlanResponse toRatePlanResponse(RatePlan ratePlan);

    @Mapping(target = "destinationType", expression = "java(rateCard.getDestinationType() != null ? rateCard.getDestinationType().name() : null)")
    @Mapping(target = "unit", expression = "java(rateCard.getUnit().name())")
    RateCardResponse toRateCardResponse(RateCard rateCard);

    @Mapping(target = "status", expression = "java(cdr.getStatus() != null ? cdr.getStatus().name() : null)")
    @Mapping(target = "destinationType", expression = "java(cdr.getDestinationType() != null ? cdr.getDestinationType().name() : null)")
    CdrResponse toCdrResponse(Cdr cdr);

    List<TransactionResponse> toTransactionResponseList(List<Transaction> transactions);

    List<PaymentResponse> toPaymentResponseList(List<Payment> payments);

    List<CdrResponse> toCdrResponseList(List<Cdr> cdrs);

    @Mapping(target = "type", expression = "java(paymentMethod.getType().name())")
    PaymentMethodResponse toPaymentMethodResponse(PaymentMethod paymentMethod);

    List<PaymentMethodResponse> toPaymentMethodResponseList(List<PaymentMethod> paymentMethods);
}
