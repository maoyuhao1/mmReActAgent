package com.mmagent.repository;

import com.mmagent.document.ConversationDocument;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import reactor.core.publisher.Flux;

public interface ConversationRepository extends ReactiveMongoRepository<ConversationDocument, String> {

    Flux<ConversationDocument> findBySessionIdOrderByTurnIndexAsc(String sessionId);

    Flux<ConversationDocument> findByStatus(String status);

    Flux<ConversationDocument> findAllByOrderByStartTimeDesc();
}
