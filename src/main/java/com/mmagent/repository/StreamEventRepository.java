package com.mmagent.repository;

import com.mmagent.document.StreamEventDocument;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import reactor.core.publisher.Flux;

public interface StreamEventRepository extends ReactiveMongoRepository<StreamEventDocument, String> {

    Flux<StreamEventDocument> findByConversationIdOrderBySequenceAsc(String conversationId);

    Flux<StreamEventDocument> findBySessionId(String sessionId);

    Flux<StreamEventDocument> findTop100ByOrderByTimestampDesc();
}
