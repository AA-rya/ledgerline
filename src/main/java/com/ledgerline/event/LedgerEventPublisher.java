package com.ledgerline.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class LedgerEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LedgerEventPublisher.class);
    private static final String TOPIC = "ledger.transaction.posted";

    private final KafkaTemplate<String, TransactionPostedEvent> kafkaTemplate;

    public LedgerEventPublisher(KafkaTemplate<String, TransactionPostedEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Fire-and-forget publish with logged failure, deliberately not
     * blocking the HTTP response on Kafka availability -- the
     * transaction is already durably committed in Postgres by the time
     * this is called; a Kafka outage should degrade downstream
     * consumers, not the ledger's own write path.
     */
    public void publishPosted(TransactionPostedEvent event) {
        kafkaTemplate.send(TOPIC, event.transactionId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish TransactionPostedEvent for tx={}",
                                event.transactionId(), ex);
                    }
                });
    }
}
