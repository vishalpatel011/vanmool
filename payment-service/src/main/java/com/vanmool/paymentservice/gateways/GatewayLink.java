package com.vanmool.paymentservice.gateways;

// The gateway's id for the link (plink_..., cs_...) and the page the customer opens to pay
public record GatewayLink(String reference, String url) {
}
