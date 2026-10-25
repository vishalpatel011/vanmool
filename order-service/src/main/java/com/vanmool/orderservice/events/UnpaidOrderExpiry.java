package com.vanmool.orderservice.events;

import com.vanmool.orderservice.services.OrderService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Every minute: cancel the orders that were not paid within the payment window (order.payment-window)
@Component
public class UnpaidOrderExpiry {
    private final OrderService orderService;

    public UnpaidOrderExpiry(OrderService orderService) {
        this.orderService = orderService;
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    public void expire() {
        orderService.expireUnpaidOrders();
    }
}
