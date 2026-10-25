package com.vanmool.paymentservice.gateways;

import com.vanmool.paymentservice.models.GatewayName;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

// Chooses the gateway for a payment, and the ones to fall back on. Large shops keep several gateways connected so
// that one gateway's outage does not stop sales: if the first cannot create a link, the next one is tried.
@Component
public class PaymentGatewaySelector {
    private final Map<GatewayName, PaymentGateway> gateways = new EnumMap<>(GatewayName.class);
    private final GatewayName defaultGateway;

    public PaymentGatewaySelector(List<PaymentGateway> gateways, @Value("${payment.default-gateway}") GatewayName defaultGateway) {
        gateways.forEach(gateway -> this.gateways.put(gateway.name(), gateway));
        this.defaultGateway = defaultGateway;
    }

    // The customer's choice if given, else the default, else any; then every other enabled gateway, in a fixed order
    public List<PaymentGateway> candidates(GatewayName requested) {
        if (requested != null) {
            require(requested);
        }
        List<PaymentGateway> ordered = new ArrayList<>();
        GatewayName first = requested != null ? requested : defaultGateway;
        PaymentGateway preferred = gateways.get(first);
        if (preferred != null && preferred.isEnabled()) {
            ordered.add(preferred);
        }
        gateways.values().stream()
                .filter(PaymentGateway::isEnabled)
                .filter(gateway -> !ordered.contains(gateway))
                .forEach(ordered::add);
        return ordered;
    }

    public PaymentGateway require(GatewayName name) {
        PaymentGateway gateway = gateways.get(name);
        if (gateway == null || !gateway.isEnabled()) {
            throw new GatewayNotEnabledException(name);
        }
        return gateway;
    }
}
